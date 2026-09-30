package com.panjia.contracts.port;

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
}
