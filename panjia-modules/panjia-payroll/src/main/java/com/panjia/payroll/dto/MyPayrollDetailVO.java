package com.panjia.payroll.dto;

import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.payroll.domain.PayrollBatch;
import com.panjia.payroll.domain.PayrollDetail;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 本人工资查询返回体：工资批次 + 本人工资明细 + 员工主数据（姓名/工号/门店名）。
 * <p>
 * 员工身份由后端按登录用户解析，不接受前端传入 employeeId。
 */
@Data
public class MyPayrollDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 工资批次 */
    private PayrollBatch batch;

    /** 本人工资明细 */
    private PayrollDetail detail;

    /** 员工主数据（employeeName/employeeCode/deptName 等，供前端直接展示，免调员工全量接口） */
    private EmployeeMainDataDTO employee;
}
