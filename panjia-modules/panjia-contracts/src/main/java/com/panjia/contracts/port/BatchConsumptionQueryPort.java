package com.panjia.contracts.port;

import lombok.Data;

/**
 * 批次消费状态查询端口（反向查询：导入域 → 业绩域）。
 * <p>
 * 导入域撤销批次前，需要校验下游是否已产生"不可撤销"的消费（如已审批的业绩调整、
 * 已审批的实收确认单、已封账期间等）。但导入域不能直接依赖业绩域，
 * 因此通过此端口做依赖反转：接口定义在 panjia-contracts，实现在 panjia-performance。
 * <p>
 * 降级策略：实现缺失或异常时，默认返回 consumed=true（保守拒绝），
 * 宁可不撤也不误撤。
 */
public interface BatchConsumptionQueryPort {

    /**
     * 检查批次是否已被下游消费（即不可撤销）。
     * <p>
     * 不可撤销的情形：
     * <ul>
     *   <li>期间已封账</li>
     *   <li>存在已审批通过的业绩调整</li>
     *   <li>存在已审批通过的实收确认单</li>
     * </ul>
     *
     * @param batchId 批次 ID
     * @param period  归属期间（YYYY-MM）
     * @return 消费检查结果，包含是否可撤及原因
     */
    RevokeCheckResult checkRevocable(Long batchId, String period);

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
