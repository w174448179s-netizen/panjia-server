package com.panjia.people.application;

import cn.dev33.satoken.exception.NotLoginException;
import com.panjia.people.domain.ChangeLog;
import com.panjia.people.domain.Employee;
import com.panjia.people.domain.EmployeeChangeTypeEnum;
import com.panjia.people.domain.EmployeeLevel;
import com.panjia.people.domain.service.EmployeeDomainService;
import com.panjia.people.infrastructure.repository.ChangeLogMapper;
import com.panjia.people.infrastructure.repository.EmployeeLevelMapper;
import com.panjia.people.infrastructure.repository.EmployeeMapper;
import com.panjia.people.infrastructure.repository.SocialInsuranceMapper;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.model.LoginUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 职级变更应用服务。
 * <p>
 * 核心规则：职级变更 = 追加新记录，旧记录设置 effective_to，禁止 DELETE/物理修改旧记录。
 */
@Slf4j
@Service
public class EmployeeLevelService {

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
    private EmployeeService employeeService;

    /**
     * 变更职级（晋升/降级）。
     *
     * @param employeeId    员工 ID
     * @param newLevelCode  新职级编码
     * @param effectiveDate 生效日期
     * @param reason        变更原因
     */
    @Transactional(rollbackFor = Exception.class)
    public void changeLevel(Long employeeId, String newLevelCode, LocalDate effectiveDate, String reason) {
        Employee employee = employeeService.loadFullEmployee(employeeId);
        EmployeeLevel current = employee.getCurrentLevel();
        String oldLevel = current == null ? null : current.getLevelCode();
        String operator = resolveOperator();

        // 构造新职级（按模板填充薪酬参数）
        EmployeeLevel newLevel = domainService.createInitialLevel(employeeId, newLevelCode, effectiveDate, reason);
        newLevel.setCreatedBy(operator);
        newLevel.setCreatedAt(LocalDateTime.now());

        // 领域行为：校验 + 关闭旧记录 + 追加新记录
        try {
            employee.changeLevel(newLevel, effectiveDate);
        } catch (IllegalStateException e) {
            throw new ServiceException(e.getMessage());
        }

        // 持久化：旧记录设置 effective_to 后更新
        if (current != null) {
            levelMapper.updateById(current);
        }
        levelMapper.insert(newLevel);

        changeLogMapper.insert(ChangeLog.create(employeeId, EmployeeChangeTypeEnum.UPDATE_LEVEL,
            "level", oldLevel, newLevelCode, reason, operator));
        log.info("员工 {} 职级变更: {} → {}，生效日 {}", employee.getEmployeeCode(), oldLevel, newLevelCode, effectiveDate);
    }

    /**
     * 查询职级历史（按生效日期倒序）。
     *
     * @param employeeId 员工 ID
     * @return 职级记录列表
     */
    @Transactional(readOnly = true)
    public List<EmployeeLevel> getLevelHistory(Long employeeId) {
        return levelMapper.selectByEmployeeIdOrderByEffectiveFromDesc(employeeId);
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
