package com.panjia.contracts.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 业绩「合同」维度摘要（performance 域对外契约 DTO）。
 * <p>
 * 按合同号聚合的业绩事实汇总，供结佣域按合同发起 / 列表合并使用；
 * 金额为业绩域原样值合计，消费方不得二次折算。
 * <p>
 * 本 DTO 只带 {@link #bizType}，不携带折算因子：折算比例属薪酬域规则，
 * 由消费方经 {@code ConversionFactorPort} 按 bizType 自行取用（避免同一口径在两域各存一份）。
 */
@Data
public class PerformanceContractSummaryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 合同号（聚合键） */
    private String contractNo;

    /**
     * 统一业务键（订单号优先/合同号优先归一后的聚合键）。
     * <p>仅按业务键跨期查询（如实收分期归属、发起页未结佣分组）返回；其余查询为 null。
     */
    private String bizKey;

    /** 正向金额合计（发起页未结佣新签按方向拆分用；与 {@link #negativeAmount} 互不抵消） */
    private BigDecimal positiveAmount;

    /** 负向金额合计（发起页未结佣新签按方向拆分用；与 {@link #positiveAmount} 互不抵消） */
    private BigDecimal negativeAmount;

    /** 涉及员工键集合（逗号分隔，跨期去重口径由消费方合并；无则为 null） */
    private String employeeKeys;

    /** 订单号（一手房展示用） */
    private String orderNo;

    /** 业务类型 */
    private String bizType;

    /** 房源地址 */
    private String propertyAddress;

    /** 签约/认购时间 */
    private LocalDateTime businessDate;

    /** 合同业绩金额合计（事实原样值求和） */
    private BigDecimal amount;

    /**
     * 实收「正向」合计（仅 PERF_REAL 合同汇总返回；跨全部实收期间合并，
     * 与 {@link #receivedNegativeAmount} 互不抵消，供结佣列表正数行展示合并实收）。
     */
    private BigDecimal receivedPositiveAmount;

    /**
     * 实收「负向」合计（仅 PERF_REAL 合同汇总返回；退单/冲正分多笔跨期合并，
     * 与 {@link #receivedPositiveAmount} 互不抵消，供结佣列表退单行展示合并实收）。
     */
    private BigDecimal receivedNegativeAmount;

    /** 实收正向行的链原值合计（调整前；与 {@link #receivedPositiveAmount} 比较判定「原值 → 调整后值」） */
    private BigDecimal receivedOriginalPositiveAmount;

    /** 实收负向行的链原值合计（调整前；与 {@link #receivedNegativeAmount} 比较判定「原值 → 调整后值」） */
    private BigDecimal receivedOriginalNegativeAmount;

    /**
     * 调整前金额合计（可空；仅实收审批通过行 listApprovedApplies 返回——审批单 received_amount 快照，
     * 与实时 amount 不一致时说明实收调整已生效，供展示「原值 → 调整后值」）。
     */
    private BigDecimal originalAmount;

    /** 应收业绩合计（PERF_REAL 查询时附带的同合同 PERF_EXPECT 合计，§3.4 差异判定用；可能为 null） */
    private BigDecimal expectedAmount;

    /**
     * 实收审批状态（PERF_REAL 查询时返回）：
     * 全部实收事实已挂 APPROVED 实收单 → APPROVED；存在未完结单 → SUBMITTED/DRAFT；
     * 无任何实收单 → null。
     */
    private String receivedStatus;

    /** 涉及签约人数（去重） */
    private long employeeCount;

    /** 归属期间 YYYY-MM（跨期汇总时取 MIN(period)，供同期互斥排除用） */
    private String period;

    /** 明细条数 */
    private long detailCount;
}
