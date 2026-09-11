package com.panjia.performance.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 业绩事实查询条件。
 * <p>
 * 支持按期间、事实口径、员工、部门、业务类型、状态、来源等多维度筛选。
 */
@Data
@NoArgsConstructor
public class FactQuery {

    /** 归属期间 YYYY-MM */
    private String period;

    /** 事实口径：PERF_REAL / PERF_EXPECT */
    private String factType;

    /** 员工 ID */
    private Long employeeId;

    /** 部门 ID */
    private Long deptId;

    /** 业务类型 */
    private String bizType;

    /** 事实状态：ACTIVE / REVERSED */
    private String factStatus;

    /** 来源：IMPORT / MANUAL */
    private String source;
}
