package com.panjia.performance.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 业绩排行行（按员工聚合的新签业绩金额降序）。
 */
@Data
public class PerformanceRankVo {

    /** 排名（从 1 开始，按当前页内序号） */
    private Long rank;

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
