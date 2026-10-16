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

    /**
     * 新签导入后重新评估该合同当月 DRAFT 实收审批单（2026-09-30 定稿）。
     * <p>评估口径同 autoCreateForReceivedBatch：新签=该订单跨月 PERF_EXPECT ACTIVE 合计，
     * 实收=审批单 receivedAmount（含历史合并入 DRAFT 的全部到账）。
     * <ul>
     *   <li>实收 ≥ 新签 → 自动通过(APPROVED) + emit ReceivedApprovedEvent；</li>
     *   <li>实收 &lt; 新签 → 启动人工审批工作流(SUBMITTED)，与导入时"有新签但不足"路径一致；</li>
     *   <li>仍无新签 → 保持 DRAFT（不应发生，新签刚导入；兜底不处理）。</li>
     * </ul>
     * 仅处理 DRAFT 单；SUBMITTED(人工流中)/APPROVED(已通过) 不打扰。
     * <p>匹配维度：合同号非空时按合同号查当月全部 DRAFT 逐单按各自订单号评估
     * （同合同多订单互不干扰）；合同号为空（一手房/家装荐客等无合同号业务，
     * 以 order_no 为锚点）时按订单号查 DRAFT。
     *
     * @param period     实收归属期间 YYYY-MM
     * @param contractNo 合同号（可空，空时按 orderNo 匹配）
     * @param orderNo    订单号（contractNo 为空时必传）
     * @param operatorId 操作人 ID（事件场景无登录上下文，null 走兜底）
     * @return true=已自动通过；false=未通过（启动人工或保持 DRAFT）
     */
    boolean resolveDraftAfterNewSign(String period, String contractNo, String orderNo, Long operatorId);

    /**
     * 业绩调整联动：合同「有新签、无实收」时，把联动建出的虚拟实收明细归入一张
     * <b>审批通过</b>的实收审批单（APPROVED，无流程实例，来源=业绩调整）。
     * <p>
     * 新签调整执行后，实收侧无 ACTIVE 明细 → 联动按逐人 delta 建虚拟实收明细
     * （source_type=MANUAL、received_apply_id 为空）。这些明细必须挂到一张审批单上，
     * 否则实收审批单列表查不到该笔实收；本方法即“直建 APPROVED 单 + 绑定明细”。
     * <p>幂等：同订单号当月已有活跃审批单（DRAFT/SUBMITTED/APPROVED）时跳过。
     *
     * @param period     实收归属期间 YYYY-MM
     * @param orderNo    订单号（可空，空时按合同号匹配）
     * @param contractNo 合同号
     * @param operatorId 操作人 ID（业绩调整执行人，兜底 1L）
     * @param adjustId   业绩调整单 ID（日志留痕；明细侧 adjust_id 已记录来源）
     * @return 新建审批单数量
     */
    int autoCreateApprovedForAdjust(String period, String orderNo, String contractNo,
                                    Long operatorId, Long adjustId);
}
