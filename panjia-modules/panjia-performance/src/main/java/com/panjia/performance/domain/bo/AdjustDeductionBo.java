package com.panjia.performance.domain.bo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 增加角色人调整单的指定扣除行（2026-09-28）。
 * <p>
 * 新角色人拿到 X 元业绩后，优先从指定的已有角色人身上精确扣除，
 * 剩余部分由未指定角色人按业绩占比等比分摊，合同总额不变。
 */
@Data
@NoArgsConstructor
public class AdjustDeductionBo {

    /** 被扣除的既有业绩事实 ID（须归属同一合同） */
    private Long factId;

    /** 扣除金额（>0，Σ扣除 ≤ 新角色人金额） */
    private BigDecimal amount;
}
