package com.panjia.contracts.port;

import com.panjia.contracts.dto.ManualReceivedSubmitResultDTO;
import com.panjia.contracts.dto.ReceivedContractInfoDTO;

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
     * 新签导入后为「已到账、未建单」的实收补建审批单（2026-10-29 去草稿）。
     * <p>先导实收、后到新签的场景不再落草稿单：实收明细已入库且 received_apply_id 为空。
     * 本方法按订单（合同号兜底）取该订单全部未绑定 ACTIVE 明细作为累计到账，
     * 评估口径同 autoCreateForReceivedBatch：
     * <ul>
     *   <li>实收 ≥ 新签 且人员一致 → 自动通过(APPROVED) + emit ReceivedApprovedEvent；</li>
     *   <li>实收 &lt; 新签 或人员不一致 → 启动人工审批工作流(SUBMITTED)；</li>
     *   <li>仍无新签 → 不建单，明细保持未绑定等下次触发。</li>
     * </ul>
     * 该订单该期间已有任一审批单时直接跳过，不重复出单。
     *
     * @param period     实收归属期间 YYYY-MM
     * @param contractNo 合同号（可空，空时按 orderNo 匹配）
     * @param orderNo    订单号（contractNo 为空时必传）
     * @param operatorId 操作人 ID（事件场景无登录上下文，null 走兜底）
     * @param signBatchId 触发的新签导入批次 ID（用于补录溯源与撤销还原；手工补录场景传 null）
     * @return true=已建单（自动通过或转人工）；false=未建单
     */
    boolean resolvePendingAfterNewSign(String period, String contractNo, String orderNo,
                                       Long operatorId, Long signBatchId);

    /**
     * 新签批次撤销：把因该批新签自动通过（APPROVED 且无流程实例）的实收单解绑明细后删除。
     * <p>去草稿后不再回退 DRAFT：明细回到「未绑定审批单」状态，即待新签匹配，
     * 下次正确新签导入后由 {@link #resolvePendingAfterNewSign} 重新建单。
     * <p>人工终审单（有流程实例）不会被前置校验放过，不在此处理范围。
     *
     * @param period     实收归属期间 YYYY-MM
     * @param bizKeys    新签批次合同号/订单号集合
     * @return 删除的审批单数量
     */
    int revokeAutoApprovedApplies(String period, java.util.Collection<String> bizKeys);

    /**
     * 新签批次撤销：清掉由该批新签触发的实收补录（补行 + 压缩）。
     * <p>先删 {@code supplement_origin_batch_id=signBatchId} 的补录行，
     * 再把被该批压缩而置为 SUPERSEDED 的原行恢复 ACTIVE（原金额完整保留）。
     * 若补录行已被并入审批单，则跳过删除（避免单据金额与明细不符），由人工调整。
     *
     * @param signBatchId 新签导入批次 ID
     * @return 清理的明细行数（删除 + 还原）
     */
    int revokeSupplements(Long signBatchId);

    /**
     * 待新签匹配的实收到账（明细已入库、未绑定审批单）。
     * <p>用于新签导入后定位「哪些订单/归属月需要补建实收审批单」；去草稿后不再有 DRAFT 单可查。
     */
    record PendingReceipt(String orderNo, String contractNo, String period) {}

    /**
     * 按业务键（订单号优先，合同号兜底）查待新签匹配的实收到账，按 (订单号, 合同号, 归属月) 去重。
     *
     * @param bizKeys 业务键集合（订单号或合同号）
     * @return 待匹配到账列表；无则空
     */
    java.util.List<PendingReceipt> findPendingReceipts(java.util.Collection<String> bizKeys);

    /**
     * 业绩调整联动：合同「有新签、无实收」时，把联动建出的虚拟实收明细归入一张
     * <b>审批通过</b>的实收审批单（APPROVED，无流程实例，来源=业绩调整）。
     * <p>
     * 新签调整执行后，实收侧无 ACTIVE 明细 → 联动按逐人 delta 建虚拟实收明细
     * （source_type=MANUAL、received_apply_id 为空）。这些明细必须挂到一张审批单上，
     * 否则实收审批单列表查不到该笔实收；本方法即“直建 APPROVED 单 + 绑定明细”。
     * <p>幂等：同订单号当月已有活跃审批单（SUBMITTED/APPROVED，去草稿后无 DRAFT）时跳过。
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

    /**
     * 实收审批自动发起结佣留痕：把结佣域自动生成的结佣申请单号回填到实收审批单，
     * 供实收审批列表/详情提示「已自动发起结佣」。
     * <p>
     * 仅审批通过的非「二手买卖」单据（系统自动通过/人工终审通过）调用；
     * 幂等：按 applyId 直接 UPDATE，审批单不存在时静默忽略。
     *
     * @param applyId             实收审批单 ID
     * @param commissionApplyNo   自动生成的结佣申请单号（CAPP 前缀）
     */
    void markAutoCommissionApply(Long applyId, String commissionApplyNo);

    /**
     * 按合同号/订单号查实收合同信息（pj_received_contract），
     * 供业绩域在「实收 DRAFT、pj_perf_fact 尚无 ACTIVE 事实」场景补全合同展示信息。
     * <p>只读查询；合同号优先（非空时按合同号查），否则按订单号查；取最新一条。
     *
     * @param contractNo 合同号（可空，空时按 orderNo 匹配）
     * @param orderNo    订单号（contractNo 为空时必传）
     * @return 合同信息；无匹配返回 null
     */
    ReceivedContractInfoDTO findContractInfo(String contractNo, String orderNo);

    /**
     * 查询某合同/订单在指定实收期间的实收金额上限（新签补录校验用）。
     * <p>口径：pj_received_apply 中同合同号（或订单号）+ 同期间、
     * 状态为 DRAFT/SUBMITTED/APPROVED 的审批单 received_amount 合计；
     * 已拒绝/已取消的单据不计。
     *
     * @param contractNo 合同号（可空，空时按 orderNo 匹配）
     * @param orderNo    订单号（contractNo 为空时必传）
     * @param period     实收归属期间 YYYY-MM（必传）
     * @return 实收金额上限；无匹配单返回 null（调用方自行决定是否放行）
     */
    java.math.BigDecimal findReceivedAmount(String contractNo, String orderNo, String period);

    /**
     * 查询某合同/订单在指定实收期间的 ACTIVE 实收明细（角色人级），
     * 供新签补录审批详情页展示「对应实收」。
     *
     * @param contractNo 合同号（可空，空时按 orderNo 匹配）
     * @param orderNo    订单号（contractNo 为空时必传）
     * @param period     实收归属期间 YYYY-MM（必传）
     * @return 实收明细列表；无匹配返回空列表
     */
    java.util.List<com.panjia.contracts.dto.ReceivedFactInfoDTO> findReceivedFacts(
        String contractNo, String orderNo, String period);
}
