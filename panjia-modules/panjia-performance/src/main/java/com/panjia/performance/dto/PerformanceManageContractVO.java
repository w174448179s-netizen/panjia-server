package com.panjia.performance.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 业绩管理「合同」维度聚合行（懒加载树表的合同层节点）。
 * <p>
 * {@code /perf/fact/manage/contract} 分页接口只返回该聚合行（每合同一行：
 * 合同号/订单号/类型/房源地址/签约日期/合同总金额/涉及人数/明细数），
 * 其下「签约人 → 明细」由 {@code /perf/fact/manage/contract/details} 按合同号懒加载。
 * <p>
 * 金额口径由 factType 决定：PERF_EXPECT=应收 / PERF_REAL=实收。
 */
@Data
@NoArgsConstructor
public class PerformanceManageContractVO {

    /** 合同号 */
    private String contractNo;

    /** 订单号 */
    private String orderNo;

    /** 业务类型（二手买卖/租赁/...） */
    private String bizType;

    /** 房源地址 */
    private String propertyAddress;

    /** 签约/认购日期时间（含时分秒） */
    private LocalDateTime businessDate;

    /** 合同金额合计（调整后，PERF_EXPECT=应收 / PERF_REAL=实收） */
    private BigDecimal amount;

    /** 原始金额合计（调整前，未调整时 = amount） */
    private BigDecimal originalAmount;

    /** 涉及签约人数（去重） */
    private long employeeCount;

    /** 明细条数 */
    private long detailCount;

    /** 未结算条数 */
    private long unsettledCount;

    /** 聚合状态（存在任一 ACTIVE → ACTIVE，否则 VOIDED） */
    private String factStatus;

    /** 折算后金额合计（amount × conversionFactor） */
    private BigDecimal convertedAmount;

    /** 折算后原始金额合计（originalAmount × conversionFactor） */
    private BigDecimal originalConvertedAmount;
}
