package com.panjia.people.service.impl;

import com.panjia.people.domain.EmployeeStatus;
import com.panjia.people.dto.EmployeeCreateDTO;
import com.panjia.people.dto.ValidatedEmployeeRow;
import com.panjia.people.port.DeptPort;
import com.panjia.people.port.EmployeeImportSink;
import com.panjia.people.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 员工导入落地实现：导入域校验通过的行 → 部门建树 + 复用新增员工全流程。
 * <p>
 * 每行原子落地：ensureDept → createEmployee（Employee + sys_user + 岗位/角色 +
 * 8 条 fact + change_log + salary_record）。操作人记为系统（0L）。
 */
@Service
@RequiredArgsConstructor
public class EmployeeImportSinkImpl implements EmployeeImportSink {

    /** 系统操作人（无人值守导入） */
    private static final Long SYSTEM_OPERATOR_ID = 0L;

    private final DeptPort deptPort;
    private final EmployeeService employeeService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Long> apply(List<ValidatedEmployeeRow> rows) {
        return rows.stream().map(this::applyRow).toList();
    }

    /**
     * 单行落地：部门全路径 → deptId，转创建 DTO 后复用员工新增流程。
     *
     * @param row 校验通过行
     * @return 新建员工 ID
     */
    private Long applyRow(ValidatedEmployeeRow row) {
        Long deptId = deptPort.ensureDept(row.getDeptFull());

        EmployeeCreateDTO dto = new EmployeeCreateDTO();
        dto.setDeptId(deptId);
        dto.setEmployeeCode(row.getEmployeeCode());
        dto.setEmployeeName(row.getEmployeeName());
        dto.setPostNames(row.getPostNames());
        dto.setLevelCode(row.getLevelCode());
        dto.setPhone(row.getPhone());
        dto.setIdCard(row.getIdCard());
        dto.setReportDate(row.getReportDate());
        dto.setHireDate(row.getHireDate());
        dto.setSocialInsured(Boolean.TRUE.equals(row.getSocialInsured()));
        dto.setHousingInsured(Boolean.TRUE.equals(row.getHousingInsured()));
        dto.setCommercialInsured(Boolean.TRUE.equals(row.getCommercialInsured()));
        dto.setDormitory(Boolean.TRUE.equals(row.getDormitory()));
        dto.setParttime(Boolean.TRUE.equals(row.getParttime()));
        dto.setMentorCode(row.getMentorCode());
        // 兼职员工初始状态 = PARTTIME，其余 ACTIVE
        dto.setStatus(Boolean.TRUE.equals(row.getParttime())
            ? EmployeeStatus.PARTTIME.getCode()
            : EmployeeStatus.ACTIVE.getCode());

        return employeeService.createEmployee(dto, SYSTEM_OPERATOR_ID);
    }
}
