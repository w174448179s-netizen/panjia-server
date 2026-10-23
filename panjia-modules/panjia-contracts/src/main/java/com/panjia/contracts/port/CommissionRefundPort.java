package com.panjia.contracts.port;

import com.panjia.contracts.dto.CommissionRefundLine;

import java.math.BigDecimal;
import java.util.List;

/**
 * 退单结佣端口：退单执行时把「已结佣部分」在退单月冲减为负结佣明细。
 * <p>
 * 设计口径（2026-10-23）：
 * <ul>
 *   <li>8 月单 9 月退 → 负新签、负实收、负结佣全部落在<b>退单月</b>；</li>
 *   <li>只有已结佣（存在 APPROVED 结佣明细）的部分需要显式生成负明细，
 *       未结佣部分由后续「发起结佣」自然读取负新签事实抵扣，避免重复冲减；</li>
 *   <li>生成的负明细直接为 APPROVED（退单已走独立审批流，不再二次审批），
 *       绑定负新签事实，发起结佣时该事实已被占用不会再重复计入。</li>
 * </ul>
 * 实现位于 panjia-commission，业绩域仅依赖本端口。
 */
public interface CommissionRefundPort {

    /**
     * 预览：合同已结佣（APPROVED 结佣明细）的新签业绩合计（正数）。
     *
     * @param contractNo 合同号
     * @param orderNo    订单号（可空）
     * @return 已结佣业绩合计；无已结佣明细返回 0
     */
    BigDecimal sumSettledExpectAmount(String contractNo, String orderNo);

    /**
     * 执行：按已结佣明细镜像生成负结佣明细（归属月=退单月，状态 APPROVED）。
     *
     * @param contractNo          合同号
     * @param orderNo             订单号（可空）
     * @param refundPeriod        退单落账月 YYYY-MM
     * @param refundId            退单 ID（溯源/幂等）
     * @param operatorId          操作人
     * @param expectFactIdMapping 原新签事实 ID → 本次生成的负事实 ID
     * @return 负结佣业绩合计（负数）；无可冲减明细返回 0
     */
    BigDecimal createRefundReversal(String contractNo, String orderNo, String refundPeriod,
                                    Long refundId, Long operatorId,
                                    java.util.Map<Long, Long> expectFactIdMapping);

    /**
     * 结佣明细行（供业绩域组装冲减行）。
     *
     * @param contractNo 合同号
     * @param orderNo    订单号
     * @return 已结佣明细行；无则返回空列表
     */
    List<CommissionRefundLine> listSettledLines(String contractNo, String orderNo);
}
