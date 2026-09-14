package com.panjia.contracts.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 实收对齐应收结果 DTO（跨域契约，panjia-contracts 叶子模块）。
 * <p>
 * 结佣审批（总监节点）发现「实收 ≠ 应收」时，经端口要求业绩域执行
 * 「实收自动对齐应收」（§3.5）：同合同下每条 PERF_REAL 事实按对应
 * PERF_EXPECT 事实口径 supersede 为新事实，合同总额与每人明细均被改写。
 * <p>
 * 结佣域据 {@link #mappings} 将申请单明细从旧事实 ID 重绑到新事实 ID、金额更新为新值。
 */
@Data
@NoArgsConstructor
public class ReceivedAlignmentResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 归属期间 YYYY-MM */
    private String period;

    /** 合同号 */
    private String contractNo;

    /** 对齐前实收合计 */
    private BigDecimal receivedTotalBefore;

    /** 应收合计（对齐目标值） */
    private BigDecimal expectedTotal;

    /** 对齐后实收合计（应与应收合计一致） */
    private BigDecimal receivedTotalAfter;

    /** 旧实收事实 → 新实收事实映射（仅列出实际发生替换的事实） */
    private List<Mapping> mappings = new ArrayList<>();

    /**
     * 单条事实替换映射。
     */
    @Data
    @NoArgsConstructor
    public static class Mapping implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 旧实收事实 ID（已 REVERSED） */
        private Long oldFactId;

        /** 新实收事实摘要（ACTIVE，金额已对齐应收口径） */
        private PerformanceFactSummaryDTO newFact;
    }
}
