package com.panjia.performance.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.EventPort;
import com.panjia.contracts.event.ImportBatchRevokedEvent;
import com.panjia.contracts.event.ReceivedApprovedEvent;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ReceivedApplyPort;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.ReceivedApply;
import com.panjia.performance.domain.ReceivedApplyStatus;
import com.panjia.performance.mapper.PerformanceConsumeLogMapper;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.mapper.ReceivedApplyMapper;
import com.panjia.performance.service.IPerfRefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 导入批次撤销事件处理器（业绩域段）。
 * <p>
 * 收到 ImportBatchRevokedEvent 后：
 * <ul>
 *   <li>硬删该批次业绩事实（pj_perf_fact）；</li>
 *   <li>硬删实收审批单（pj_perf_received_apply）及关联流程实例（走 ApprovalPort 系统级链路）；</li>
 *   <li>硬删消费日志（pj_perf_consume_log）；</li>
 *   <li>硬删本批次自动登记的导入退单及其负结佣冲减单（pj_perf_refund 物理删除，
       负结佣冲减单先作废为 REVERSED/CANCELLED 留痕后删退单）；</li>
 *   <li><b>保底恢复（2026-10-02）</b>：restoredBatchIds 非空时，把被冲销旧批次的
 *       REVERSED/SUPERSEDE 事实翻回 ACTIVE，并对恢复合同重放实收通过事件
 *       （结佣域据此重建 DRAFT 草稿单）；</li>
 *   <li>新签批次撤销且无旧批次恢复时，把因这批新签自动通过（无流程实例）的实收单
 *       回退为 DRAFT，等待下次正确新签导入重新触发自动通过。</li>
 * </ul>
 * <p>
 * 幂等：重复消费安全（删除/状态翻转/事件重放均幂等，结佣建单自身带活跃单判重）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportBatchRevokedHandler implements DomainEventHandler {

    private static final String SOURCE_TYPE_KE_SIGNED = "KE_SIGNED";

    private final PerformanceFactMapper factMapper;
    private final ReceivedApplyMapper receivedApplyMapper;
    private final PerformanceConsumeLogMapper consumeLogMapper;
    private final ApprovalPort approvalPort;
    private final ReceivedApplyPort receivedApplyPort;
    private final EventPort eventPort;
    private final IPerfRefundService perfRefundService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return ImportBatchRevokedEvent.EVENT_TYPE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(String eventId, String payloadJson) {
        ImportBatchRevokedEvent event;
        try {
            event = objectMapper.readValue(payloadJson, ImportBatchRevokedEvent.class);
        } catch (JacksonException e) {
            log.error("[批次撤销-业绩域] 事件反序列化失败：eventId={}", eventId, e);
            return;
        }
        Long batchId = event.getBatchId();
        String period = event.getPeriod();
        List<Long> restoredBatchIds = parseIdList(event.getRestoredBatchIds());

        log.info("[批次撤销-业绩域] 开始处理：batchId={}, sourceType={}, period={}, restoredBatchIds={}",
            batchId, event.getSourceType(), period, restoredBatchIds);

        // 删除前留存本批次新签合同/订单号（事实删后无法反查；供无恢复时回退自动通过实收单）
        Set<String> batchContractKeys = loadExpectBizKeys(batchId);

        // ① 硬删该批次的所有业绩事实
        int factCount = factMapper.delete(new LambdaQueryWrapper<PerformanceFact>()
            .eq(PerformanceFact::getBatchId, batchId));
        log.info("[批次撤销-业绩域] 业绩事实已删除：batchId={}, count={}", batchId, factCount);

        // ② 硬删实收审批单及其关联流程实例（系统级删除：事件处理器无登录上下文，不走权限校验与业务事件）
        List<ReceivedApply> applies = receivedApplyMapper.selectList(
            new LambdaQueryWrapper<ReceivedApply>().eq(ReceivedApply::getBatchId, batchId));
        if (!applies.isEmpty()) {
            List<Long> bizIds = applies.stream().map(ReceivedApply::getId).toList();
            // 删流程实例（含任务/历史/实例/业务扩展；自动通过单无实例，cancelBatch 需幂等容忍）
            approvalPort.cancelBatch(bizIds);
            log.info("[批次撤销-业绩域] 关联流程实例删除：batchId={}, applyCount={}",
                batchId, applies.size());
            receivedApplyMapper.delete(
                new LambdaQueryWrapper<ReceivedApply>().eq(ReceivedApply::getBatchId, batchId));
            log.info("[批次撤销-业绩域] 实收审批单已删除：batchId={}, count={}",
                batchId, applies.size());
        }

        // ③ 硬删消费日志
        int logCount = consumeLogMapper.delete(new LambdaQueryWrapper<com.panjia.performance.domain.PerformanceConsumeLog>()
            .eq(com.panjia.performance.domain.PerformanceConsumeLog::getBatchId, batchId));

        // ③.5 撤销本批次自动登记的导入退单（含其负结佣冲减单）：
        // 事实已硬删，退单残留会导致合同仍被标记「已退单」、退单管理出幽灵记录、结佣发起被误拦截
        int cancelledRefunds = perfRefundService.cancelImportedRefundsByBatch(batchId);
        if (cancelledRefunds > 0) {
            log.info("[批次撤销-业绩域] 导入退单已撤销：batchId={}, count={}", batchId, cancelledRefunds);
        }

        // ④ 保底恢复：旧批次事实翻回 ACTIVE
        int restoredFacts = 0;
        Set<String> restoredContractKeys = new LinkedHashSet<>();
        if (!restoredBatchIds.isEmpty()) {
            restoredFacts = factMapper.restoreSupersededFacts(restoredBatchIds);
            restoredContractKeys = loadExpectBizKeysByBatches(restoredBatchIds);
            log.info("[批次撤销-业绩域] 旧批次事实已恢复：restoredBatchIds={}, count={}, contracts={}",
                restoredBatchIds, restoredFacts, restoredContractKeys.size());
        }

        // ⑤ 实收联动
        if (!restoredContractKeys.isEmpty()) {
            // 5a. 有旧批次恢复：对恢复合同重放实收通过事件，结佣域重建 DRAFT 草稿单
            replayReceivedApproved(period, restoredContractKeys, event.getOperatorId());
        } else if (SOURCE_TYPE_KE_SIGNED.equals(event.getSourceType()) && !batchContractKeys.isEmpty()) {
            // 5b. 新签整批撤销且无旧版本：自动通过的实收单失去新签依据 → 解绑明细后删除单（去草稿，不回退 DRAFT），
            //     明细回到「未绑定审批单」= 待新签匹配，下次新签导入由 resolvePendingAfterNewSign 重建
            int rolledBack = rollbackAutoApprovedApplies(period, batchContractKeys);
            log.info("[批次撤销-业绩域] 自动通过实收单已解绑并删除：batchId={}, count={}", batchId, rolledBack);
        }

        // 5c. 新签批次撤销：清掉该批新签触发的实收补录（补行 + 压缩还原），
        //     否则残留的补录行会在下次实收导入时与新文件叠加，导致实收业绩虚增
        if (SOURCE_TYPE_KE_SIGNED.equals(event.getSourceType())) {
            int cleaned = receivedApplyPort.revokeSupplements(batchId);
            log.info("[批次撤销-业绩域] 实收补录已清理：batchId={}, count={}", batchId, cleaned);
        }

        log.info("[批次撤销-业绩域] 完成：batchId={}, 删除事实={}, 删除实收单={}, 删除消费日志={}, 恢复事实={}",
            batchId, factCount, applies.size(), logCount, restoredFacts);
    }

    /** 查批次内 PERF_EXPECT 事实的合同号/订单号集合（删除前快照）。 */
    private Set<String> loadExpectBizKeys(Long batchId) {
        return loadExpectBizKeysByBatches(List.of(batchId));
    }

    /** 查给定批次集合内 ACTIVE PERF_EXPECT 事实的合同号/订单号（合同号、订单号都收，查询时双键兜底）。 */
    private Set<String> loadExpectBizKeysByBatches(List<Long> batchIds) {
        List<PerformanceFact> facts = factMapper.selectList(
            new LambdaQueryWrapper<PerformanceFact>()
                .in(PerformanceFact::getBatchId, batchIds)
                .eq(PerformanceFact::getFactType, FactType.PERF_EXPECT)
                .eq(PerformanceFact::getFactStatus, FactStatus.ACTIVE));
        Set<String> keys = new LinkedHashSet<>();
        for (PerformanceFact f : facts) {
            if (f.getContractNo() != null && !f.getContractNo().isBlank()) {
                keys.add(f.getContractNo().trim());
            }
            if (f.getOrderNo() != null && !f.getOrderNo().isBlank()) {
                keys.add(f.getOrderNo().trim());
            }
        }
        return keys;
    }

    /**
     * 对恢复合同当月 APPROVED 实收单重放实收通过事件。
     * <p>新事件在本事务提交后的下一轮 Outbox 投递；outbox 留痕并幂等重放，
     * 供结佣域消费方接入后按事件重建（当前结佣由业务人员对 APPROVED 实收手工发起）。
     */
    private void replayReceivedApproved(String period, Set<String> contractKeys, Long operatorId) {
        if (period == null || contractKeys.isEmpty()) {
            return;
        }
        List<ReceivedApply> approvedApplies = receivedApplyMapper.selectList(
            new LambdaQueryWrapper<ReceivedApply>()
                .eq(ReceivedApply::getPeriod, period)
                .eq(ReceivedApply::getStatus, ReceivedApplyStatus.APPROVED)
                .and(w -> w.in(ReceivedApply::getContractNo, contractKeys)
                    .or().in(ReceivedApply::getOrderNo, contractKeys))
                .orderByDesc(ReceivedApply::getId));
        // 同一合同可能命中多单（合同号/订单号交叉），按合同归一只重放最新一张
        Set<String> replayedKeys = new LinkedHashSet<>();
        int replayed = 0;
        for (ReceivedApply apply : approvedApplies) {
            String dedupKey = apply.getContractNo() != null ? apply.getContractNo() : apply.getOrderNo();
            if (dedupKey != null && !replayedKeys.add(dedupKey)) {
                continue;
            }
            ReceivedApprovedEvent replay = new ReceivedApprovedEvent();
            replay.setApplyId(apply.getId());
            replay.setPeriod(apply.getPeriod());
            replay.setOrderNo(apply.getOrderNo());
            replay.setContractNo(apply.getContractNo());
            replay.setBizType(apply.getBizType());
            replay.setOperatorId(operatorId);
            // 撤销重放属于补偿动作，不触发结佣自动发起（避免批次撤销后重复建结佣单）
            replay.setAutoApproved(false);
            eventPort.emit(replay);
            replayed++;
        }
        log.info("[批次撤销-业绩域] 实收通过事件重放：period={}, 合同数={}, 重放={}",
            period, contractKeys.size(), replayed);
    }

    /**
     * 无旧批次恢复的新签撤销：把因该批新签自动通过（APPROVED 且无流程实例）的实收单
     * 解绑明细后删除（委托 received 域 {@link ReceivedApplyPort#revokeAutoApprovedApplies}）。
     * <p>去草稿后不再回退 DRAFT：明细回到「未绑定审批单」状态，即待新签匹配，
     * 下次正确新签导入后由 resolvePendingAfterNewSign 重新建单。
     * <p>人工终审单（有流程实例）已被撤销前置校验拦截，不会到达此处。
     */
    private int rollbackAutoApprovedApplies(String period, Set<String> contractKeys) {
        if (StringUtils.isBlank(period) || contractKeys.isEmpty()) {
            return 0;
        }
        return receivedApplyPort.revokeAutoApprovedApplies(period, contractKeys);
    }

    /** 事件中的字符串 ID 列表安全转 Long（跳过非法值）。 */
    private List<Long> parseIdList(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            try {
                result.add(Long.valueOf(id));
            } catch (NumberFormatException ignored) {
                log.warn("[批次撤销-业绩域] 非法 restoredBatchId 已忽略：{}", id);
            }
        }
        return result;
    }
}
