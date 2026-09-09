package com.panjia.people.application;

import cn.dev33.satoken.exception.NotLoginException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.contracts.event.EmployeeRoleChangedEvent;
import com.panjia.contracts.event.EventPort;
import com.panjia.contracts.exception.BizCode;
import com.panjia.people.application.dto.EmployeeCreateDTO;
import com.panjia.people.application.dto.EmployeeDTO;
import com.panjia.people.application.dto.EmployeeUpdateDTO;
import com.panjia.people.domain.ChangeLog;
import com.panjia.people.domain.Employee;
import com.panjia.people.domain.EmployeeChangeTypeEnum;
import com.panjia.people.domain.EmployeeLevel;
import com.panjia.people.domain.EmployeeRoleEnum;
import com.panjia.people.domain.SocialInsuranceProfile;
import com.panjia.people.domain.service.EmployeeDomainService;
import com.panjia.people.infrastructure.repository.ChangeLogMapper;
import com.panjia.people.infrastructure.repository.EmployeeLevelMapper;
import com.panjia.people.infrastructure.repository.EmployeeMapper;
import com.panjia.people.infrastructure.repository.SocialInsuranceMapper;
import com.panjia.people.interface_.converter.EmployeeConverter;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.model.LoginUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 员工应用服务 —— 员工档案 CRUD 用例编排。
 * <p>
 * 写操作 {@code @Transactional(rollbackFor = Exception.class)}；读操作 {@code readOnly = true}。
 */
@Slf4j
@Service
public class EmployeeService {

    @Autowired
    private EmployeeMapper employeeMapper;
    @Autowired
    private EmployeeLevelMapper levelMapper;
    @Autowired
    private SocialInsuranceMapper socialInsuranceMapper;
    @Autowired
    private ChangeLogMapper changeLogMapper;
    @Autowired
    private EmployeeDomainService domainService;
    @Autowired
    private MentorRelationService mentorRelationService;
    @Autowired
    private EventPort eventPort;

    /**
     * 创建员工：校验工号唯一 → 建员工 + 初始职级 + 社保档案 → 写变更日志。
     *
     * @param dto 创建 DTO
     * @return 员工 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createEmployee(EmployeeCreateDTO dto) {
        if (employeeMapper.existsByEmployeeCode(dto.getEmployeeCode())) {
            throw new ServiceException("工号已存在: " + dto.getEmployeeCode(), BizCode.EMPLOYEE_CODE_DUPLICATE);
        }
        String operator = resolveOperator();
        LocalDateTime now = LocalDateTime.now();

        Employee employee = EmployeeConverter.toDomain(dto);
        employee.setCreatedBy(operator);
        employee.setCreatedAt(now);
        employee.setUpdatedAt(now);
        employeeMapper.insert(employee);

        // 初始职级
        EmployeeLevel initialLevel = domainService.createInitialLevel(
            employee.getId(), dto.getLevelCode(), employee.getHireDate(), "初始化");
        initialLevel.setCreatedBy(operator);
        initialLevel.setCreatedAt(now);
        levelMapper.insert(initialLevel);

        // 初始社保档案（个人比例缺省 0.20）
        BigDecimal personalRatio = dto.getSocialInsuranceRatio() == null
            ? new BigDecimal("0.20") : dto.getSocialInsuranceRatio();
        SocialInsuranceProfile profile = domainService.createSocialInsurance(
            employee.getId(), personalRatio, employee.getHireDate());
        profile.setCreatedBy(operator);
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        socialInsuranceMapper.insert(profile);

        // 变更日志
        changeLogMapper.insert(ChangeLog.create(employee.getId(), EmployeeChangeTypeEnum.CREATE,
            null, null, dto.getEmployeeCode(), "初始化建档", operator));

        log.info("员工建档成功: code={}, id={}", employee.getEmployeeCode(), employee.getId());
        return employee.getId();
    }

    /**
     * 更新员工基础档案（不含职级变更）。
     *
     * @param id  员工 ID
     * @param dto 更新 DTO
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateEmployee(Long id, EmployeeUpdateDTO dto) {
        Employee existing = loadFullEmployee(id);
        String operator = resolveOperator();

        // 角色变更留痕：检测旧角色 → 新角色，写 ROLE_CHANGE 日志 + emit 事件
        if (dto.getRole() != null && existing.getRole() != null
            && !dto.getRole().equals(existing.getRole().name())) {
            String oldRole = existing.getRole().name();
            String newRole = dto.getRole();
            changeLogMapper.insert(ChangeLog.create(id, EmployeeChangeTypeEnum.ROLE_CHANGE,
                "role", oldRole, newRole, dto.getReason(), operator));

            // 事务内 emit 事件 → pj_event_outbox，监听器消费后联动 sys_user_role
            EmployeeRoleChangedEvent event = new EmployeeRoleChangedEvent();
            event.setEmployeeId(id);
            event.setUserId(existing.getUserId());
            event.setOldRole(oldRole);
            event.setNewRole(newRole);
            event.setReason(dto.getReason());
            event.setOperator(operator);
            eventPort.emit(event);

            log.info("员工角色变更: id={}, {} → {}", id, oldRole, newRole);
        }

        EmployeeConverter.applyUpdate(existing, dto);
        existing.setUpdatedBy(operator);
        existing.setUpdatedAt(LocalDateTime.now());
        employeeMapper.updateById(existing);

        changeLogMapper.insert(ChangeLog.create(id, EmployeeChangeTypeEnum.UPDATE_BASE,
            null, null, "基础档案变更", dto.getReason(), operator));
    }

    /**
     * 员工离职：设置状态 + 关闭职级 + 失效师徒关系 + 写日志。
     *
     * @param id         员工 ID
     * @param resignDate 离职日期
     */
    @Transactional(rollbackFor = Exception.class)
    public void resign(Long id, LocalDate resignDate) {
        Employee employee = loadFullEmployee(id);
        EmployeeLevel current = employee.getCurrentLevel();
        String operator = resolveOperator();
        // 记录离职前状态（resign() 会改写为 RESIGNED）
        String oldStatus = employee.getStatus() == null ? null : employee.getStatus().name();

        try {
            employee.resign(resignDate);
        } catch (IllegalStateException e) {
            throw new ServiceException(e.getMessage());
        }
        employee.setUpdatedBy(operator);
        employee.setUpdatedAt(LocalDateTime.now());
        employeeMapper.updateById(employee);

        // 关闭当前职级记录（旧记录 effective_to = 离职日）
        if (current != null && current.getEffectiveTo() != null) {
            levelMapper.updateById(current);
        }
        // 失效师徒关系（徒弟离职，已发奖励不追回）
        mentorRelationService.deactivateByApprenticeResign(id);

        changeLogMapper.insert(ChangeLog.create(id, EmployeeChangeTypeEnum.RESIGN,
            "status", oldStatus, "RESIGNED", "离职", operator));
        log.info("员工离职: id={}, resignDate={}", id, resignDate);
    }

    /**
     * 按工号查询员工（导入匹配用）。
     *
     * @param employeeCode 工号
     * @return 员工聚合根（含职级/社保），不存在返回 null
     */
    @Transactional(readOnly = true)
    public Employee findByEmployeeCode(String employeeCode) {
        Employee employee = employeeMapper.selectByEmployeeCode(employeeCode);
        if (employee != null) {
            assembleRelations(employee);
        }
        return employee;
    }

    /**
     * 分页查询员工档案。
     *
     * @param pageQuery 分页参数
     * @param deptId    部门筛选（可空）
     * @param role      角色筛选（可空）
     * @return 分页结果
     */
    @Transactional(readOnly = true)
    public PageResult<EmployeeDTO> page(PageQuery pageQuery, Long deptId, String role) {
        EmployeeRoleEnum roleEnum = null;
        if (role != null && !role.isBlank()) {
            try {
                roleEnum = EmployeeRoleEnum.valueOf(role);
            } catch (IllegalArgumentException e) {
                throw new ServiceException("无效的人员角色: " + role);
            }
        }
        LambdaQueryWrapper<Employee> wrapper = new LambdaQueryWrapper<Employee>()
            .eq(deptId != null, Employee::getDeptId, deptId)
            .eq(roleEnum != null, Employee::getRole, roleEnum)
            .orderByDesc(Employee::getId);
        Page<Employee> page = employeeMapper.selectPage(pageQuery.build(), wrapper);
        List<EmployeeDTO> list = page.getRecords().stream().map(EmployeeConverter::toDTO).toList();
        return PageResult.build(list, page.getTotal());
    }

    /**
     * 加载员工完整聚合（含职级历史与社保档案）。
     *
     * @param id 员工 ID
     * @return 员工聚合根
     * @throws ServiceException 员工不存在
     */
    @Transactional(readOnly = true)
    public Employee loadFullEmployee(Long id) {
        Employee employee = employeeMapper.selectById(id);
        if (employee == null) {
            throw new ServiceException("员工不存在: " + id, BizCode.EMPLOYEE_NOT_FOUND);
        }
        assembleRelations(employee);
        return employee;
    }

    /**
     * 装配员工的职级历史与社保档案。
     *
     * @param employee 员工聚合根
     */
    private void assembleRelations(Employee employee) {
        List<EmployeeLevel> levels = levelMapper.selectByEmployeeIdOrderByEffectiveFromDesc(employee.getId());
        employee.setLevelHistory(levels);
        employee.setSocialInsurance(socialInsuranceMapper.selectByEmployeeId(employee.getId()));
    }

    /**
     * 解析当前操作人登录名，未登录兜底 system。
     *
     * @return 操作人 login_name
     */
    private String resolveOperator() {
        try {
            LoginUser loginUser = LoginHelper.getLoginUser();
            return loginUser.getUsername();
        } catch (NotLoginException e) {
            return "system";
        }
    }
}
