package com.panjia.performance.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 业绩调整单查询条件。
 */
@Data
@NoArgsConstructor
public class AdjustQuery {

    /** 归属期间 YYYY-MM */
    private String period;

    /** 调整类型：AMOUNT / VOID / TRANSFER */
    private String adjustType;

    /** 状态：SUBMITTED / APPROVED / REJECTED / CANCELLED / EXECUTED */
    private String status;

    /** 员工 ID */
    private Long employeeId;

    /** 部门 ID */
    private Long deptId;
}
