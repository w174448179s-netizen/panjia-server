package com.panjia.people.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.people.domain.ChangeLog;
import com.panjia.people.domain.Employee;
import com.panjia.people.domain.EmployeeStatus;
import com.panjia.people.domain.FactType;
import com.panjia.people.domain.SalaryFact;
import com.panjia.people.domain.SalaryRecord;
import com.panjia.people.dto.AccountUserInfo;
import com.panjia.people.dto.ChangeLogVO;
import com.panjia.people.dto.EmployeeCreateDTO;
import com.panjia.people.dto.EmployeeQuery;
import com.panjia.people.dto.EmployeeUpdateDTO;
import com.panjia.people.dto.EmployeeVO;
import com.panjia.people.mapper.ChangeLogMapper;
import com.panjia.people.mapper.EmployeeMapper;
import com.panjia.people.mapper.SalaryFactMapper;
import com.panjia.people.mapper.SalaryRecordMapper;
import com.panjia.people.port.AccountPort;
import com.panjia.people.port.DeptPort;
import com.panjia.people.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 员工服务实现（V5.2）。
 * <p>
 * 只依赖 Port 接口操作 sys_*；变更统一走 {@code changeFact}（闭开区间），
 * 部门/岗位变更同事务同步账户，离职禁用账户。
 */
@Service
@RequiredArgsConstructor
public class EmployeeServiceImpl implements EmployeeService {

    /** 变更字段：入职初始化 */
    private static final String CHANGE_FIELD_ALL = "ALL";
    /** 变更字段：归属部门 */
    private static final String CHANGE_FIELD_DEPT = "DEPT";
    /** 变更字段：岗位与角色 */
    private static final String CHANGE_FIELD_POSTS = "POSTS";
    /** 布尔事实真值 */
    private static final String BOOL_TRUE = "true";
    /** 布尔事实假值 */
    private static final String BOOL_FALSE = "false";
    /** 无师傅时 MENTOR 事实存储值 */
    private static final String NO_MENTOR = "";
    /** 系统操作人（导入等无人值守场景） */
    private static final Long SYSTEM_OPERATOR_ID = 0L;

    private final EmployeeMapper employeeMapper;
    private final SalaryFactMapper salaryFactMapper;
    private final SalaryRecordMapper salaryRecordMapper;
    private final ChangeLogMapper changeLogMapper;
    private final AccountPort accountPort;
    private final DeptPort deptPort;

    // ==================== 查询 ====================

    @Override
    public PageResult<EmployeeVO> pageList(EmployeeQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<Employee> wrapper = new LambdaQueryWrapper<Employee>()
            .like(StringUtils.isNotBlank(query.getEmployeeCode()), Employee::getEmployeeCode, query.getEmployeeCode())
            .like(StringUtils.isNotBlank(query.getEmployeeName()), Employee::getEmployeeName, query.getEmployeeName())
            .eq(StringUtils.isNotBlank(query.getStatus()),
                Employee::getStatus, EmployeeStatus.fromCode(query.getStatus()))
            .orderByDesc(Employee::getCreateTime);

        if (query.getDeptId() != null) {
            List<Long> deptIds = deptPort.findDeptAndChildIds(query.getDeptId());
            wrapper.in(Employee::getDeptId, deptIds);
        }
        if (StringUtils.isNotBlank(query.getPostName())) {
            List<Long> userIds = accountPort.findUserIdsByPostName(query.getPostName());
            if (userIds.isEmpty()) {
                return PageResult.build(List.of(), 0L);
            }
            wrapper.in(Employee::getUserId, userIds);
        }

        Page<Employee> page = employeeMapper.selectPage(pageQuery.build(), wrapper);
        List<EmployeeVO> vos = page.getRecords().stream().map(this::toVO).toList();
        enrich(vos);
        return PageResult.build(vos, page.getTotal());
    }

    @Override
    public EmployeeVO getDetail(Long employeeId) {
        Employee emp = employeeMapper.selectById(employeeId);
        if (emp == null) {
            throw new ServiceException("员工不存在，employeeId={}", employeeId);
        }
        EmployeeVO vo = toVO(emp);
        enrich(List.of(vo));
        return vo;
    }

    @Override
    public List<ChangeLogVO> getHistory(Long employeeId) {
        List<ChangeLog> logs = changeLogMapper.selectByEmployee(employeeId);
        Set<Long> operatorIds = logs.stream()
            .map(ChangeLog::getOperatorId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, String> operatorNames = operatorIds.isEmpty()
            ? Map.of()
            : accountPort.findNicknamesByIds(operatorIds);

        return logs.stream().map(log -> {
            ChangeLogVO vo = new ChangeLogVO();
            BeanUtil.copyProperties(log, vo);
            vo.setChangeFieldName(displayFieldName(log.getChangeField()));
            vo.setOperatorName(log.getOperatorId() != null ? operatorNames.get(log.getOperatorId()) : null);
            if (FactType.MENTOR.getCode().equals(log.getChangeField())) {
                vo.setBeforeValue(displayMentor(log.getBeforeValue()));
                vo.setAfterValue(displayMentor(log.getAfterValue()));
            }
            return vo;
        }).toList();
    }

    // ==================== 新增 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createEmployee(EmployeeCreateDTO dto, Long operatorId) {
        Long exists = employeeMapper.selectCount(new LambdaQueryWrapper<Employee>()
            .eq(Employee::getEmployeeCode, dto.getEmployeeCode()));
        if (exists != null && exists > 0) {
            throw new ServiceException("工号 {} 已存在", dto.getEmployeeCode());
        }
        Long mentorId = resolveMentorIdByCode(dto.getMentorCode());

        Employee emp = new Employee();
        emp.setEmployeeCode(dto.getEmployeeCode().trim());
        emp.setEmployeeName(dto.getEmployeeName().trim());
        emp.setDeptId(dto.getDeptId());
        emp.setPhone(dto.getPhone());
        emp.setIdCard(dto.getIdCard());
        emp.setReportDate(dto.getReportDate());
        emp.setHireDate(dto.getHireDate());
        EmployeeStatus status = EmployeeStatus.fromCode(dto.getStatus());
        emp.setStatus(status != null ? status : EmployeeStatus.ACTIVE);
        emp.setMentorEmployeeId(mentorId);
        emp.setRemark(dto.getRemark());
        employeeMapper.insert(emp);

        // 建系统账户 + 岗位/角色（同事务）
        Long userId = accountPort.createUser(
            emp.getEmployeeCode(), emp.getEmployeeName(), emp.getDeptId(), dto.getPostNames());
        emp.setUserId(userId);
        employeeMapper.updateById(emp);

        Long operator = operatorId != null ? operatorId : SYSTEM_OPERATOR_ID;
        LocalDate effect = emp.getHireDate();
        // 8 类算薪事实初始记录（change_field=ALL，生效日=入职日）
        initFact(emp.getEmployeeId(), FactType.LEVEL, dto.getLevelCode(), effect);
        initFact(emp.getEmployeeId(), FactType.STATUS, emp.getStatus().getCode(), effect);
        initFact(emp.getEmployeeId(), FactType.SOCIAL, boolVal(dto.getSocialInsured()), effect);
        initFact(emp.getEmployeeId(), FactType.HOUSING, boolVal(dto.getHousingInsured()), effect);
        initFact(emp.getEmployeeId(), FactType.COMMERCIAL, boolVal(dto.getCommercialInsured()), effect);
        initFact(emp.getEmployeeId(), FactType.DORMITORY, boolVal(dto.getDormitory()), effect);
        initFact(emp.getEmployeeId(), FactType.PARTTIME, boolVal(dto.getParttime()), effect);
        initFact(emp.getEmployeeId(), FactType.MENTOR,
            mentorId != null ? String.valueOf(mentorId) : NO_MENTOR, effect);

        writeLog(emp.getEmployeeId(), CHANGE_FIELD_ALL, null, "入职初始化", effect, operator);
        refreshRecord(emp);
        return emp.getEmployeeId();
    }

    // ==================== 修改（统一 diff） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateEmployee(Long employeeId, EmployeeUpdateDTO dto, Long operatorId) {
        Employee emp = employeeMapper.selectById(employeeId);
        if (emp == null) {
            throw new ServiceException("员工不存在，employeeId={}", employeeId);
        }
        Long operator = operatorId != null ? operatorId : SYSTEM_OPERATOR_ID;
        LocalDate effect = dto.getEffectiveDate() != null ? dto.getEffectiveDate() : LocalDate.now();

        // ① 基本信息直改
        if (StringUtils.isNotBlank(dto.getEmployeeName())) {
            emp.setEmployeeName(dto.getEmployeeName().trim());
        }
        if (dto.getPhone() != null) {
            emp.setPhone(dto.getPhone());
        }
        if (dto.getIdCard() != null) {
            emp.setIdCard(dto.getIdCard());
        }
        if (dto.getReportDate() != null) {
            emp.setReportDate(dto.getReportDate());
        }
        if (dto.getHireDate() != null) {
            emp.setHireDate(dto.getHireDate());
        }
        if (dto.getRemark() != null) {
            emp.setRemark(dto.getRemark());
        }
        if (dto.getLeaveDate() != null) {
            emp.setLeaveDate(dto.getLeaveDate());
        }

        // ② 师傅（mentorCode 非 null 即参与比对；空白=解除师傅关系）
        if (dto.getMentorCode() != null) {
            Long newMentorId = resolveMentorIdByCode(dto.getMentorCode());
            if (!Objects.equals(newMentorId, emp.getMentorEmployeeId())) {
                changeFact(employeeId, FactType.MENTOR,
                    newMentorId != null ? String.valueOf(newMentorId) : NO_MENTOR, effect, operator);
                emp.setMentorEmployeeId(newMentorId);
            }
        }

        // ③ 状态（离职 → 禁用账户）
        if (StringUtils.isNotBlank(dto.getStatus())) {
            EmployeeStatus newStatus = EmployeeStatus.fromCode(dto.getStatus());
            if (newStatus == null) {
                throw new ServiceException("非法员工状态：{}", dto.getStatus());
            }
            if (newStatus != emp.getStatus()) {
                changeFact(employeeId, FactType.STATUS, newStatus.getCode(), effect, operator);
                emp.setStatus(newStatus);
                if (newStatus == EmployeeStatus.LEFT) {
                    emp.setLeaveDate(dto.getLeaveDate() != null ? dto.getLeaveDate() : effect);
                    if (emp.getUserId() != null) {
                        accountPort.disableUser(emp.getUserId());
                    }
                }
            }
        }

        // ④ 职级（只走 fact，与登录/权限无关）
        if (StringUtils.isNotBlank(dto.getLevelCode())) {
            String current = salaryFactMapper.selectValueAt(employeeId, FactType.LEVEL, effect);
            if (!dto.getLevelCode().trim().equals(current)) {
                changeFact(employeeId, FactType.LEVEL, dto.getLevelCode().trim(), effect, operator);
            }
        }

        // ⑤ 算薪开关（只走 fact）
        changeBoolFact(employeeId, FactType.SOCIAL, dto.getSocialInsured(), effect, operator);
        changeBoolFact(employeeId, FactType.HOUSING, dto.getHousingInsured(), effect, operator);
        changeBoolFact(employeeId, FactType.COMMERCIAL, dto.getCommercialInsured(), effect, operator);
        changeBoolFact(employeeId, FactType.DORMITORY, dto.getDormitory(), effect, operator);
        changeBoolFact(employeeId, FactType.PARTTIME, dto.getParttime(), effect, operator);

        // ⑥ 归属部门变更 → 同步 sys_user.dept_id
        if (dto.getDeptId() != null && !dto.getDeptId().equals(emp.getDeptId())) {
            String beforeName = deptPort.findDeptFullName(emp.getDeptId());
            if (emp.getUserId() != null) {
                accountPort.updateDept(emp.getUserId(), dto.getDeptId());
            }
            emp.setDeptId(dto.getDeptId());
            String afterName = deptPort.findDeptFullName(dto.getDeptId());
            writeLog(employeeId, CHANGE_FIELD_DEPT, beforeName, afterName, effect, operator);
        }

        // ⑦ 岗位集合变更 → 重建 sys_user_post + 推导重建 sys_user_role（先清后绑）
        if (dto.getPostNames() != null && emp.getUserId() != null) {
            List<String> target = dto.getPostNames().stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .sorted()
                .distinct()
                .toList();
            AccountUserInfo info = accountPort.loadUser(emp.getUserId());
            List<String> current = info != null && info.getPostNames() != null
                ? info.getPostNames().stream().sorted().distinct().toList()
                : List.of();
            if (!current.equals(target)) {
                accountPort.rebuildPostsAndRoles(emp.getUserId(), target);
                writeLog(employeeId, CHANGE_FIELD_POSTS,
                    String.join("/", current), String.join("/", target), effect, operator);
            }
        }

        employeeMapper.updateById(emp);
        refreshRecord(emp);
    }

    // ==================== 算薪快照（PeopleQueryPort） ====================

    @Override
    public Map<String, String> getSnapshotAt(Long employeeId, LocalDate pointInMonth) {
        LocalDate point = monthEnd(pointInMonth);
        Map<String, String> snap = new LinkedHashMap<>();
        for (FactType type : FactType.values()) {
            snap.put(type.getCode(), salaryFactMapper.selectValueAt(employeeId, type, point));
        }
        return snap;
    }

    @Override
    public Map<Long, Map<String, String>> getSnapshotsAt(Collection<Long> employeeIds, LocalDate pointInMonth) {
        LocalDate point = monthEnd(pointInMonth);
        List<Long> ids = List.copyOf(employeeIds);
        Map<Long, Map<String, String>> result = new LinkedHashMap<>();
        for (Long id : ids) {
            Map<String, String> snap = new LinkedHashMap<>();
            for (FactType type : FactType.values()) {
                snap.put(type.getCode(), null);
            }
            result.put(id, snap);
        }
        for (FactType type : FactType.values()) {
            for (SalaryFact fact : salaryFactMapper.selectValuesAt(ids, type, point)) {
                result.get(fact.getEmployeeId()).put(type.getCode(), fact.getValue());
            }
        }
        return result;
    }

    // ==================== 内部方法 ====================

    /**
     * 入职初始事实（change_field=ALL，不闭合旧区间）。
     */
    private void initFact(Long employeeId, FactType type, String value, LocalDate effect) {
        SalaryFact fact = new SalaryFact();
        fact.setEmployeeId(employeeId);
        fact.setFactType(type);
        fact.setValue(value != null ? value : NO_MENTOR);
        fact.setEffectiveDate(effect);
        fact.setChangeField(CHANGE_FIELD_ALL);
        salaryFactMapper.insert(fact);
    }

    /**
     * 通用变更入口：闭合旧区间 → 新增事实 → 写日志。分字段独立时间线。
     */
    private void changeFact(Long employeeId, FactType type, String newValue,
                            LocalDate effect, Long operatorId) {
        String oldValue = salaryFactMapper.selectValueAt(employeeId, type, effect);
        salaryFactMapper.closeActiveAt(employeeId, type, effect);

        SalaryFact fact = new SalaryFact();
        fact.setEmployeeId(employeeId);
        fact.setFactType(type);
        fact.setValue(newValue);
        fact.setEffectiveDate(effect);
        fact.setChangeField(type.getCode());
        salaryFactMapper.insert(fact);

        writeLog(employeeId, type.getCode(), oldValue, newValue, effect, operatorId);
    }

    /**
     * 布尔事实变更（新值为 null 表示本次不修改）。
     */
    private void changeBoolFact(Long employeeId, FactType type, Boolean newValue,
                                LocalDate effect, Long operatorId) {
        if (newValue == null) {
            return;
        }
        String target = boolVal(newValue);
        String current = salaryFactMapper.selectValueAt(employeeId, type, effect);
        if (!target.equals(current)) {
            changeFact(employeeId, type, target, effect, operatorId);
        }
    }

    /**
     * 写变更日志。
     */
    private void writeLog(Long employeeId, String changeField, String beforeValue,
                          String afterValue, LocalDate effect, Long operatorId) {
        ChangeLog log = new ChangeLog();
        log.setEmployeeId(employeeId);
        log.setChangeField(changeField);
        log.setBeforeValue(beforeValue);
        log.setAfterValue(afterValue);
        log.setEffectiveDate(effect);
        log.setOperatorId(operatorId);
        changeLogMapper.insert(log);
    }

    /**
     * 按最新事实切片刷新当前态物化记录。
     */
    private void refreshRecord(Employee emp) {
        LocalDate point = LocalDate.now();
        SalaryRecord record = new SalaryRecord();
        record.setEmployeeId(emp.getEmployeeId());
        record.setDeptId(emp.getDeptId());
        record.setStatus(emp.getStatus());
        record.setLevelCode(salaryFactMapper.selectValueAt(emp.getEmployeeId(), FactType.LEVEL, point));
        record.setSocialInsured(parseBool(salaryFactMapper.selectValueAt(emp.getEmployeeId(), FactType.SOCIAL, point)));
        record.setHousingInsured(parseBool(salaryFactMapper.selectValueAt(emp.getEmployeeId(), FactType.HOUSING, point)));
        record.setCommercialInsured(parseBool(salaryFactMapper.selectValueAt(emp.getEmployeeId(), FactType.COMMERCIAL, point)));
        record.setDormitory(parseBool(salaryFactMapper.selectValueAt(emp.getEmployeeId(), FactType.DORMITORY, point)));
        record.setIsPartTime(parseBool(salaryFactMapper.selectValueAt(emp.getEmployeeId(), FactType.PARTTIME, point)));
        String mentorValue = salaryFactMapper.selectValueAt(emp.getEmployeeId(), FactType.MENTOR, point);
        record.setMentorEmployeeId(StringUtils.isNotBlank(mentorValue) ? Long.valueOf(mentorValue) : null);
        salaryRecordMapper.upsert(record);
    }

    /**
     * 师傅工号 → 师傅员工 ID；空白返回 null；不存在抛异常。
     */
    private Long resolveMentorIdByCode(String mentorCode) {
        if (StringUtils.isBlank(mentorCode)) {
            return null;
        }
        Employee mentor = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
            .eq(Employee::getEmployeeCode, mentorCode.trim())
            .last("LIMIT 1"));
        if (mentor == null) {
            throw new ServiceException("师傅工号 {} 不存在", mentorCode);
        }
        return mentor.getEmployeeId();
    }

    /**
     * 员工实体 → 视图（不含部门名/岗位/算薪态，由 enrich 批量填充）。
     */
    private EmployeeVO toVO(Employee emp) {
        EmployeeVO vo = new EmployeeVO();
        BeanUtil.copyProperties(emp, vo);
        return vo;
    }

    /**
     * 批量填充视图：算薪当前态、部门全路径、岗位名集合、师傅信息。
     */
    private void enrich(List<EmployeeVO> vos) {
        if (vos.isEmpty()) {
            return;
        }
        List<Long> employeeIds = vos.stream().map(EmployeeVO::getEmployeeId).toList();

        // 算薪当前态
        Map<Long, SalaryRecord> records = new LinkedHashMap<>();
        salaryRecordMapper.selectByIds(employeeIds)
            .forEach(r -> records.put(r.getEmployeeId(), r));
        for (EmployeeVO vo : vos) {
            SalaryRecord record = records.get(vo.getEmployeeId());
            if (record != null) {
                vo.setLevelCode(record.getLevelCode());
                vo.setSocialInsured(record.getSocialInsured());
                vo.setHousingInsured(record.getHousingInsured());
                vo.setCommercialInsured(record.getCommercialInsured());
                vo.setDormitory(record.getDormitory());
                vo.setIsPartTime(record.getIsPartTime());
                if (vo.getMentorEmployeeId() == null) {
                    vo.setMentorEmployeeId(record.getMentorEmployeeId());
                }
            }
        }

        // 部门全路径名
        Set<Long> deptIds = vos.stream().map(EmployeeVO::getDeptId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> deptNames = deptPort.findDeptFullNames(deptIds);
        vos.forEach(vo -> vo.setDeptName(deptNames.get(vo.getDeptId())));

        // 岗位名集合
        List<Long> userIds = vos.stream().map(EmployeeVO::getUserId).filter(Objects::nonNull).toList();
        Map<Long, List<String>> postNames = accountPort.findPostNamesByUserIds(userIds);
        vos.forEach(vo -> vo.setPostNames(
            vo.getUserId() != null ? postNames.getOrDefault(vo.getUserId(), List.of()) : List.of()));

        // 师傅姓名/工号
        Set<Long> mentorIds = vos.stream().map(EmployeeVO::getMentorEmployeeId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Employee> mentors = new LinkedHashMap<>();
        if (!mentorIds.isEmpty()) {
            employeeMapper.selectByIds(mentorIds).forEach(m -> mentors.put(m.getEmployeeId(), m));
        }
        for (EmployeeVO vo : vos) {
            Employee mentor = vo.getMentorEmployeeId() != null ? mentors.get(vo.getMentorEmployeeId()) : null;
            if (mentor != null) {
                vo.setMentorName(mentor.getEmployeeName());
                vo.setMentorCode(mentor.getEmployeeCode());
            }
        }
    }

    /**
     * 变更字段中文名（fact_type 取枚举描述，特殊字段显式命名）。
     */
    private String displayFieldName(String changeField) {
        FactType type = FactType.fromCode(changeField);
        if (type != null) {
            return type.getDesc();
        }
        return switch (changeField) {
            case CHANGE_FIELD_ALL -> "入职初始化";
            case CHANGE_FIELD_DEPT -> "归属部门";
            case CHANGE_FIELD_POSTS -> "岗位/角色";
            default -> changeField;
        };
    }

    /**
     * MENTOR 事实值（员工 ID）→ 展示文案。
     */
    private String displayMentor(String value) {
        if (StringUtils.isBlank(value)) {
            return "无师傅";
        }
        Employee mentor = employeeMapper.selectById(Long.valueOf(value));
        return mentor == null ? value : mentor.getEmployeeName() + "（" + mentor.getEmployeeCode() + "）";
    }

    /**
     * 取月份最后一天作为切片点（月内最新事实）。
     */
    private LocalDate monthEnd(LocalDate date) {
        return date.withDayOfMonth(date.lengthOfMonth());
    }

    /**
     * 布尔 → fact 存储值。
     */
    private String boolVal(Boolean value) {
        return Boolean.TRUE.equals(value) ? BOOL_TRUE : BOOL_FALSE;
    }

    /**
     * fact 存储值 → 布尔。
     */
    private Boolean parseBool(String value) {
        return BOOL_TRUE.equals(value);
    }
}
