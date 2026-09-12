package com.panjia.performance.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 业绩管理「签约人」维度聚合行（懒加载树表的人层节点）。
 * <p>
 * {@code /perf/fact/manage} 分页接口只返回该聚合行（每人一行），
 * 其下「合同 → 明细」由 {@code /perf/fact/manage/details} 按员工懒加载。
 * <p>
 * 两个 Tab 共用同一 VO（金额口径由 factType 决定：PERF_EXPECT=应收 / PERF_REAL=实收）。
 */
@Data
@NoArgsConstructor
public class PerformanceManageEmployeeVO {

    /** 员工 ID */
    private Long employeeId;

    /** 工号 */
    private String employeeCode;

    /** 员工姓名（签约人） */
    private String employeeName;

    /** 金额合计（PERF_EXPECT=应收 / PERF_REAL=实收） */
    private BigDecimal amount;

    /** 合同数（按合同号去重） */
    private long contractCount;

    /** 明细条数 */
    private long detailCount;

    /** 未结算条数 */
    private long unsettledCount;
}
