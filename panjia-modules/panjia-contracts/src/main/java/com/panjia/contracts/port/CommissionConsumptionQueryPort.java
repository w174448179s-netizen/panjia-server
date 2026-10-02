package com.panjia.contracts.port;

import lombok.Data;

import java.util.Collection;

/**
 * 结佣消费状态查询端口（反向查询：业绩域 → 结佣域）。
 * <p>
 * 导入批次撤销前，需要判断批次涉及的合同是否已进入结佣审批流程。业绩域不能直接
 * 依赖结佣域，因此通过此端口做依赖反转：接口定义在 panjia-contracts，
 * 实现在 panjia-commission，由 panjia-performance 的撤销前置校验统一调用。
 * <p>
 * 口径（2026-10-02 保底撤销）：
 * <ul>
 *   <li>结佣草稿单 DRAFT（实收自动通过后产生、未提交、无流程实例）不算已结佣，
 *       撤销批次时随批删除并在旧批次恢复后重建，故放行；</li>
 *   <li>已提交/已通过且持有流程实例、已驳回的结佣单视为已结佣，禁止撤销；</li>
 *   <li>已锁定 LOCKED 一律禁止撤销（无论是否有流程实例）——月结佣审批通过并锁定后，
 *       其依据的导入批次不允许再撤回；</li>
 *   <li>存在结佣调整单（流程性单据）一律禁止撤销；</li>
 *   <li>匹配只按合同/订单业务键，不按批次期间过滤：结佣单挂实收月，其明细可引用
 *       更早月份的新签事实，跨月锁定单也必须拦截。</li>
 * </ul>
 * <p>
 * 降级策略：实现缺失或异常时，调用方按保守拒绝处理（宁可不撤也不误撤）。
 */
public interface CommissionConsumptionQueryPort {

    /**
     * 检查指定期间内、给定合同/订单集合是否已产生不可随批撤销的结佣消费。
     *
     * @param period      归属期间（YYYY-MM）
     * @param contractNos 合同号集合（可空）
     * @param orderNos    订单号集合（可空，合同号缺失时兜底）
     * @return 检查结果，包含是否可撤销及拒绝原因
     */
    RevokeCheckResult checkRevocable(String period,
                                     Collection<String> contractNos,
                                     Collection<String> orderNos);

    /**
     * 撤销检查结果。
     */
    @Data
    class RevokeCheckResult {
        /** 是否可撤销 */
        private boolean revocable;
        /** 不可撤销的原因（revocable=false 时填充） */
        private String reason;

        public static RevokeCheckResult ok() {
            RevokeCheckResult r = new RevokeCheckResult();
            r.setRevocable(true);
            return r;
        }

        public static RevokeCheckResult reject(String reason) {
            RevokeCheckResult r = new RevokeCheckResult();
            r.setRevocable(false);
            r.setReason(reason);
            return r;
        }
    }
}
