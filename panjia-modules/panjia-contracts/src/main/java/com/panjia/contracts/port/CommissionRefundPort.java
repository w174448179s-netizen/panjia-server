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
     * 执行（部分退单）：按已结佣明细镜像生成负结佣明细，金额按红冲比例缩放。
     * <p>
     * 贝壳导入退单允许部分退（如 8 月退一部分、9 月再退剩余），本次红冲的负新签事实
     * 金额可能只是原事实的一部分，负结佣须同比例冲减；比例缺失（该已结佣事实本次未被
     * 红冲）时该明细不生成负行，留待后续退单处理。
     *
     * @param contractNo             合同号
     * @param orderNo                订单号（可空）
     * @param refundPeriod           退单落账月 YYYY-MM
     * @param refundId               退单 ID（溯源/幂等）
     * @param operatorId             操作人
     * @param expectFactIdMapping    原新签事实 ID → 本次生成的负事实 ID
     * @param ratioByOriginalFactId  原新签事实 ID → 红冲比例（负数，-0.5 = 冲减一半）；
     *                               为 null 时按全额冲减（手工退单口径）
     * @return 负结佣业绩合计（负数）；无可冲减明细返回 0
     */
    BigDecimal createRefundReversal(String contractNo, String orderNo, String refundPeriod,
                                    Long refundId, Long operatorId,
                                    java.util.Map<Long, Long> expectFactIdMapping,
                                    java.util.Map<Long, java.math.BigDecimal> ratioByOriginalFactId);

    /**
     * 撤销退单冲减：重导旧批次被冲销时，把该退单此前生成的负结佣冲减单作废
     * （明细置 REVERSED、单据置 CANCELLED），避免重导后新旧两笔负结佣重复扣减。
     *
     * @param refundId   退单 ID（对应冲减单号 {@code TD + refundId}）
     * @param operatorId 操作人
     * @return 作废的负明细条数；未找到冲减单返回 0
     */
    int cancelRefundReversal(Long refundId, Long operatorId);

    /**
     * 结佣明细行（供业绩域组装冲减行）。
     *
     * @param contractNo 合同号
     * @param orderNo    订单号
     * @return 已结佣明细行；无则返回空列表
     */
    List<CommissionRefundLine> listSettledLines(String contractNo, String orderNo);
}
