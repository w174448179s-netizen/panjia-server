package com.panjia.performance.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 实收审批单详情·每人明细行（实收明细页详情弹窗展示用）。
 * <p>
 * 列口径对齐「合同业绩明细」页：门店/组别、工号、姓名、所属角色、角色占比、应收金额、实收金额。
 * 应收金额由 selectReceivedFactDetails 按「同合同 + 同员工工号 + 同角色」配对
 * ACTIVE PERF_EXPECT 事实聚合（双键精确，双侧闸门通过时退化按合同号）。
 */
@Data
@NoArgsConstructor
public class ReceivedFactDetailVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实收事实 ID（pj_perf_fact.id，fact_type=PERF_REAL） */
    private Long factId;

    /** 归属期间（实收合同结算月 YYYY-MM；跨期详情按期间逐笔展示） */
    private String period;

    /** 员工 ID */
    private Long employeeId;

    /** 工号 */
    private String employeeCode;

    /** 姓名 */
    private String employeeName;

    /** 门店/组别（「集团-门店-组别」，与业绩明细页 deptPath 同口径） */
    private String deptPath;

    /** 角色类型 code（归一化优先） */
    private String roleType;

    /** 角色名称（原始录入） */
    private String roleName;

    /** 角色占比 */
    private BigDecimal shareRatio;

    /** 应收金额（同 sourceKey 的 PERF_EXPECT 事实金额，已含调整） */
    private BigDecimal expectedAmount;

    /**
     * 应收原始金额（调整前：同 sourceKey + 同 fact_type 下最早一条 REVERSED 事实的金额；
     * 未调整时 = expectedAmount）。口径与「合同业绩明细」页 originalAmount 一致，
     * 仅用于前端展示「原值 → 调整后值」。
     */
    private BigDecimal originalExpectedAmount;

    /** 该行的应收已被调整（同 sourceKey 存在 REVERSED 的 PERF_EXPECT 事实） */
    private Boolean expectedAdjusted;

    /** 实收金额（PERF_REAL 事实金额，已含调整） */
    private BigDecimal amount;

    /**
     * 实收原始金额（调整前：同 sourceKey + 同 fact_type 下最早一条事实的金额；
     * 未调整时 = amount）。调整 supersede 事实时保留 sourceKey，故与应收侧
     * originalExpectedAmount 同口径，仅用于前端展示「原值 → 调整后值」。
     */
    private BigDecimal originalAmount;

    /** 该行的实收已被调整（同 sourceKey 存在 REVERSED 的 PERF_REAL 事实） */
    private Boolean receivedAdjusted;

    /** 应收业绩折算后金额（expectedAmount × conversionFactor） */
    private BigDecimal expectedConvertedAmount;

    /** 应收业绩折算后原始金额（originalExpectedAmount × conversionFactor） */
    private BigDecimal originalConvertedAmount;

    /**
     * 对应新签·全期累计口径（累计到本行期间为止的 ACTIVE 净额；跨期详情按角色合并行时
     * 取最晚期间行的该值 = 全期净额，避免「两笔实收对应一笔新签」时行级口径多期重复显示翻倍）
     */
    private BigDecimal expectedCumAmount;

    /** 对应新签原值·全期累计口径（跨期合并行原值取数用） */
    private BigDecimal expectedCumOriginalAmount;

    /** 归属审批单号（rd.received_apply_id 关联；跨期合并时按 TD 前缀区分退单冲减行与正常行） */
    private String applyNo;
}
