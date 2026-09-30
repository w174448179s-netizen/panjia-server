package com.panjia.contracts.port;

import com.panjia.contracts.dto.ManualReceivedSubmitResultDTO;

import java.util.Collection;

/**
 * 实收业绩审批单跨域端口（received 域对外契约）。
 * <p>
 * 定义在 panjia-contracts 叶子模块，实现方为 panjia-received，
 * performance 域（ImportBatchArchivedHandler / PerformanceEngine）只依赖本端口，
 * <b>严禁直连 IReceivedApplyService</b>（否则形成 performance ↔ received 循环依赖）。
 * <p>
 * 实收域内部仍可通过 panjia-performance 的 Entity/Mapper 操作数据层，
 * 但对外只暴露本端口接口。
 */
public interface ReceivedApplyPort {

    /**
     * 导入批次归档后自动建单并提交（§2.1：有实收 → 自动提交，流转财务→总监）。
     * <p>幂等：仅处理批次内 received_apply_id 尚未绑定的实收事实；同合同已有审批单时合并。
     *
     * @param batchId    导入批次 ID
     * @param period     归属期间 YYYY-MM
     * @param operatorId 操作人 ID（归档操作发起人，用于设置审批单创建人）
     * @return 新建审批单数量（合并不计）
     */
    int autoCreateForBatch(Long batchId, String period, Long operatorId);

    /**
     * 历史工资导入批次归档直建实收审批单（已结算/已对账场景，跳过人工审批）。
     *
     * @param batchId    导入批次 ID
     * @param period     归属期间 YYYY-MM
     * @param operatorId 操作人 ID
     * @return 新建审批单数量
     */
    int autoCreateApprovedForBatch(Long batchId, String period, Long operatorId);

    /**
     * 贝壳实收导入批次分流建单（按「角色人到账 vs 新签应收」自动判定直建/人工审批）。
     *
     * @param batchId    导入批次 ID
     * @param period     归属期间 YYYY-MM
     * @param operatorId 操作人 ID
     * @return 新建审批单数量
     */
    int autoCreateForReceivedBatch(Long batchId, String period, Long operatorId);

    /**
     * 从 PERF_REAL 事实 ID 列表按订单号分组建实收审批单并提交（手工提交实收路径）。
     * <p>与 IReceivedApplyService.createApplyForRealFacts 同口径，但参数用 factId 列表
     * 避免跨模块传递 PerformanceFact 实体（contracts 层不得依赖 performance 域）。
     *
     * @param factIds    PERF_REAL 事实 ID 集合（已 ACTIVE、已造好，按 orderNo 分组建单）
     * @param period     归属期间 YYYY-MM
     * @param operatorId 操作人 ID
     * @param batchId    关联批次（可 null，手工提交场景不传）
     * @return 新建审批单数量（合并不计）
     */
    int createApplyForRealFacts(Collection<Long> factIds, String period, Long operatorId, Long batchId);

    /**
     * 手工提交实收完整入口：按业务键（合同号/订单号）查 ACTIVE PERF_EXPECT 应收，
     * 由实收域在 pj_received_detail/contract 镜像造实收明细（source_type=MANUAL），
     * 再按订单号分组建实收审批单走审批流。
     * <p>拆表后 performance 域不再插 PERF_REAL 事实，本方法是手工提交的唯一落地处。
     *
     * @param bizKeys    合同号/订单号集合
     * @param period     归属期间 YYYY-MM
     * @param operatorId 操作人 ID
     * @return 提交结果（新建明细数 + 新建审批单数 + 跳过原因）
     */
    ManualReceivedSubmitResultDTO manualSubmitReceived(Collection<String> bizKeys, String period, Long operatorId);
}
