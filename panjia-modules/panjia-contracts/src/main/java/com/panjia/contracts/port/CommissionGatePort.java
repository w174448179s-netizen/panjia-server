package com.panjia.contracts.port;

import java.util.Collection;

/**
 * 结佣闸门跨域端口（commission 域对外契约，供 performance 域在发起/执行新签调整前校验）。
 * <p>
 * 依赖方向：performance → contracts ← commission。performance 不能直连 {@code pj_commission_*}
 * 表，经此端口查询结佣单状态，避免新签事实 supersede 破坏已审批锁定的结佣数据。
 * <p>
 * 规则（结佣域详细设计 §3.1 状态机）：
 * <ul>
 *   <li>结佣单 LOCKED（审批通过并锁定，已计入/将计入工资）→ 新签调整必须拒绝；
 *       已审批结佣明细金额已固化，新签事实调整会导致两者不一致，须先作废结佣单再调整新签；</li>
 *   <li>SUBMITTED（审批中）/ DRAFT（待发起）→ 不拦截：SUBMITTED 单由冲销联动
 *       回退 DRAFT 并按调整后新签金额重建明细，DRAFT 单直接重建。</li>
 * </ul>
 */
public interface CommissionGatePort {

    /**
     * 判断指定合同 + 业绩归属月的结佣单是否已审批通过锁定（LOCKED）。
     * <p>
     * 供 performance 发起新签调整前校验：LOCKED 单的结佣明细金额已固化并计入工资，
     * 新签事实调整会破坏一致性，必须先作废结佣单再调整。
     *
     * @param period     业绩归属月 YYYY-MM（新签事实原月，即调整单 originalPeriod）
     * @param contractNo 真实合同号（非订单号）
     * @return true=该合同该期间存在 LOCKED 结佣单，禁止新签调整；false=无 LOCKED 单可调整
     */
    boolean isCommissionLocked(String period, String contractNo);

    /**
     * 判断这批新签事实是否已被<b>已审批锁定</b>的结佣明细绑定。
     * <p>
     * 供新签调整发起/执行时校验，事实级口径：只拦「本次将被 supersede 且已结佣」的事实，
     * 同合同其他月份尚未结佣的新签仍可正常调整（不受该合同历史结佣单影响）。
     *
     * @param factIds 待调整（将被 supersede）的新签事实 ID
     * @return true=其中至少一条已被 LOCKED 结佣单的明细绑定，禁止调整；false=可调整
     */
    boolean areFactsLockedByCommission(Collection<Long> factIds);
}
