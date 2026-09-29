package com.panjia.performance.domain.bo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 合同级调整的明细指定值行（2026-09-28 可编辑表格交互）。
 * <p>
 * 调整弹窗中经纪人明细为可编辑表格：每行直接指定「调整后业绩金额」与可选的
 * 「角色占比」目标值，审批通过执行时按指定值精确落库（不再等比分摊）。
 */
@Data
@NoArgsConstructor
public class AdjustDetailTargetBo {

    /** 既有业绩事实 ID（须归属同一合同；新角色人行不带 factId，走 BO 顶级新人字段） */
    private Long factId;

    /** 该行调整后业绩金额（允许负数=红冲） */
    private BigDecimal targetAmount;

    /** 该行调整后角色占比（可空=不修改占比；>0，不强制合计=100%） */
    private BigDecimal shareRatio;
}
