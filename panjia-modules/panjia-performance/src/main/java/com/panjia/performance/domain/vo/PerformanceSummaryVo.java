package com.panjia.performance.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 业绩汇总报表行（按期间 + 员工聚合的新签业绩）。
 * <p>
 * 维度由查询参数 periodType 决定：MONTH 返回 YYYY-MM、QUARTER 返回 YYYY-Qn、YEAR 返回 YYYY。
 */
@Data
public class PerformanceSummaryVo {

    /** 期间标签（YYYY-MM / YYYY-Qn / YYYY） */
    private String period;

    /** 员工 ID */
    private Long employeeId;

    /** 员工姓名 */
    private String employeeName;

    /** 工号 */
    private String employeeCode;

    /** 部门名称 */
    private String deptName;

    /** 部门 ID */
    private Long deptId;

    /** 合同数（去重合同号/订单号） */
    private Integer contractCount;

    /** 新签业绩金额合计（PERF_EXPECT ACTIVE） */
    private BigDecimal totalAmount;
}
