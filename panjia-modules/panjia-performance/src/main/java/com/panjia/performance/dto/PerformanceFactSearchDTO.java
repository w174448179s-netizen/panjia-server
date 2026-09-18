package com.panjia.performance.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 完整业绩查询 DTO（合同维度聚合）。
 * <p>
 * 以合同为维度，展示该合同在新签业绩（PERF_EXPECT）和实收业绩（PERF_REAL）上的完整情况：
 * 新签金额、实收金额、调整状态与金额、实收审批状态、结佣状态。
 */
@Data
public class PerformanceFactSearchDTO {

    /** 合同号 */
    private String contractNo;

    /** 订单号 */
    private String orderNo;

    /** 业务类型 */
    private String bizType;

    /** 物业地址 */
    private String propertyAddress;

    /** 签约日期 */
    private LocalDateTime signDate;

    /** 业绩归属期间 */
    private String period;

    /** 新签业绩金额合计（ACTIVE 的 PERF_EXPECT，调整后当前值） */
    private BigDecimal expectAmount;

    /** 新签金额合计（调整前：按 source_key 回溯 REVERSED 事实，未调整时 = expectAmount） */
    private BigDecimal expectOriginalAmount;

    /** 实收业绩金额合计（ACTIVE 的 PERF_REAL） */
    private BigDecimal realAmount;

    /** 是否有调整（EXISTS pj_perf_adjust WHERE contract_no = ?） */
    private Boolean hasAdjust;

    /**
     * 新签业绩折算后金额（expectAmount × 本行 bizType 的当前生效折算因子）。
     * 因子经 {@code ConversionFactorPort} 取用，本 DTO 不携带因子本身。
     */
    private BigDecimal expectConvertedAmount;

    /** 调整前新签业绩折算后金额（expectOriginalAmount × 本行 bizType 的折算因子） */
    private BigDecimal originalExpectConvertedAmount;

    /** 实收业绩折算后金额（realAmount × 本行 bizType 的折算因子） */
    private BigDecimal realConvertedAmount;

    /** 结佣业绩折算后金额（commissionAmount × 本行 bizType 的折算因子） */
    private BigDecimal commissionConvertedAmount;

    /** 调整单状态（最近一条） */
    private String adjustStatus;

    /** 调整单号（最近一条） */
    private String adjustNo;

    /** 调整类型（最近一条） */
    private String adjustType;

    /** 实收审批单状态（最近一条） */
    private String receivedStatus;

    /** 实收审批单号 */
    private String receivedApplyNo;

    /** 实收审批单应收金额 */
    private BigDecimal receivedExpectedAmount;

    /** 实收审批单实收金额 */
    private BigDecimal receivedRealAmount;

    /** 结佣申请状态（最近一条） */
    private String commissionStatus;

    /** 结佣申请单号 */
    private String commissionApplyNo;

    /** 结佣金额 */
    private BigDecimal commissionAmount;

    /** 涉及人数 */
    private Integer employeeCount;

    /** 明细条数 */
    private Integer detailCount;
}
