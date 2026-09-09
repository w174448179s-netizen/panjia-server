package com.panjia.people.application;

import com.panjia.people.application.dto.EmployeeCreateDTO;
import com.panjia.people.application.dto.ImportResult;
import com.panjia.people.domain.Employee;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 员工批量导入服务。
 * <p>
 * 规则：按工号匹配 —— 已存在则跳过建档，不存在则建档；逐条容错，失败收集到 {@link ImportResult}。
 * <p>
 * ⚠️ 本方法刻意不加 @Transactional：每条 {@link EmployeeService#createEmployee} 自带独立事务，
 * 若外层再包一个事务，任一行失败会把共享事务标记为 rollback-only，
 * 提交时抛 UnexpectedRollbackException 导致整批回滚，逐条容错失效。
 */
@Slf4j
@Service
public class EmployeeImportService {

    @Autowired
    private EmployeeService employeeService;

    /**
     * 批量导入员工。
     *
     * @param employees 员工创建数据列表
     * @return 导入结果（成功数 + 失败明细）
     */
    public ImportResult importEmployees(List<EmployeeCreateDTO> employees) {
        ImportResult result = new ImportResult();
        for (EmployeeCreateDTO dto : employees) {
            try {
                Employee existing = employeeService.findByEmployeeCode(dto.getEmployeeCode());
                if (existing != null) {
                    // 已存在：跳过建档（基础档案变更走 EmployeeService.updateEmployee）
                    log.debug("导入匹配到已存在员工，跳过: code={}", dto.getEmployeeCode());
                } else {
                    employeeService.createEmployee(dto);
                }
                result.incrementSuccess();
            } catch (Exception e) {
                log.warn("员工导入失败: code={}, reason={}", dto.getEmployeeCode(), e.getMessage());
                result.addFailure(dto.getEmployeeCode(), e.getMessage());
            }
        }
        return result;
    }
}
