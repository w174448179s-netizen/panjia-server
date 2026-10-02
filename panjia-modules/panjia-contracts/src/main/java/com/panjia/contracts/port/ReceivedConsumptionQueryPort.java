package com.panjia.contracts.port;

import lombok.Data;

import java.util.List;

/**
 * 实收消费状态查询端口（反向查询：业绩域 → 实收域）。
 * <p>
 * 导入批次撤销前的前置校验在业绩域统一编排，但 KE_RECEIVED 批次的业绩事实已物理
 * 拆到实收域（pj_received_contract / pj_received_detail），业绩域拿不到本批实收
 * 合同集合与明细调整痕迹，故通过此端口做依赖反转：接口定义在 panjia-contracts，
 * 实现在 panjia-received。
 * <p>
 * 服务于「有调整不能撤销」口径（2026-10-02 保底撤销）：
 * <ul>
 *   <li>结佣调整单执行时会 supersede 实收明细，调整后新行 adjust_id 非空
 *       （源码约定「批次冲销会跳过」）——本批明细存在 adjust_id 非空行即说明
 *       已被结佣调整触碰，硬删会产生调整孤儿，必须拦截；</li>
 *   <li>审批中、尚未执行的结佣调整单还不会落 adjust_id，需用本批实收合同号/订单号
 *       集合交结佣域校验（CommissionConsumptionQueryPort）按单拦截；</li>
 *   <li>实收明细可能合并进其他批次创建的审批单（apply.batch_id 非本批），
 *       故合同集合不能只从本批审批单取，必须以本批实收合同行为准。</li>
 * </ul>
 * 降级策略：实现缺失或异常时，调用方按保守拒绝处理（宁可不撤也不误撤）。
 */
public interface ReceivedConsumptionQueryPort {

    /**
     * 加载本批实收合同的业务键范围（pj_received_contract.batch_id = batchId）。
     *
     * @param batchId 撤销的导入批次 ID
     * @return 本批合同号/订单号集合（去空白去重；无数据时为空列表集合）
     */
    BatchReceivedScope loadBatchScope(Long batchId);

    /**
     * 统计本批实收明细中已被调整单触碰的行数（pj_received_detail.source_batch_id
     * = batchId 且 adjust_id 非空；含调整 supersede 产生的新生效行）。
     *
     * @param batchId 撤销的导入批次 ID
     * @return 带调整痕迹的明细行数
     */
    long countAdjustedDetails(Long batchId);

    /**
     * 批次实收范围（结佣消费校验入参）。
     */
    @Data
    class BatchReceivedScope {
        /** 合同号集合（去空白去重） */
        private List<String> contractNos;
        /** 订单号集合（去空白去重） */
        private List<String> orderNos;
    }
}
