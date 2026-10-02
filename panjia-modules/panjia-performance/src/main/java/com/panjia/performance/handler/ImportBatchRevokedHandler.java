package com.panjia.performance.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.EventPort;
import com.panjia.contracts.event.ImportBatchRevokedEvent;
import com.panjia.contracts.event.ReceivedApprovedEvent;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.ReceivedApply;
import com.panjia.performance.domain.ReceivedApplyStatus;
import com.panjia.performance.mapper.PerformanceConsumeLogMapper;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.mapper.ReceivedApplyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final EventPort eventPort;
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
            // 5b. 新签整批撤销且无旧版本：自动通过的实收单失去新签依据，回退 DRAFT 等待新签重新触发
            int rolledBack = rollbackAutoApprovedApplies(period, batchContractKeys);
            log.info("[批次撤销-业绩域] 自动通过实收单回退 DRAFT：batchId={}, count={}", batchId, rolledBack);
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
     * <p>新事件在本事务提交后的下一轮 Outbox 投递才消费，此时本批 DRAFT 结佣单已被
     * 结佣域撤销处理器删除，重建无冲突；结佣建单自带活跃单判重，重复重放幂等。
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
            replay.setOperatorId(operatorId);
            eventPort.emit(replay);
            replayed++;
        }
        log.info("[批次撤销-业绩域] 实收通过事件重放：period={}, 合同数={}, 重放={}",
            period, contractKeys.size(), replayed);
    }

    /**
     * 无旧批次恢复的新签撤销：自动通过直建（APPROVED 且无流程实例）的实收单回退为 DRAFT，
     * 清空终审痕迹；明细绑定保留，待下次正确新签导入由 resolveDraftAfterNewSign 重新判定。
     * <p>人工终审单（有流程实例）已被撤销前置校验拦截，不会到达此处。
     */
    private int rollbackAutoApprovedApplies(String period, Set<String> contractKeys) {
        List<ReceivedApply> targets = receivedApplyMapper.selectList(
            new LambdaQueryWrapper<ReceivedApply>()
                .eq(ReceivedApply::getPeriod, period)
                .eq(ReceivedApply::getStatus, ReceivedApplyStatus.APPROVED)
                .isNull(ReceivedApply::getProcessInstanceId)
                .and(w -> w.in(ReceivedApply::getContractNo, contractKeys)
                    .or().in(ReceivedApply::getOrderNo, contractKeys)));
        if (targets.isEmpty()) {
            return 0;
        }
        List<Long> ids = new ArrayList<>(targets.stream().map(ReceivedApply::getId).toList());
        LambdaUpdateWrapper<ReceivedApply> update = new LambdaUpdateWrapper<ReceivedApply>()
            .in(ReceivedApply::getId, ids)
            .set(ReceivedApply::getStatus, ReceivedApplyStatus.DRAFT)
            .set(ReceivedApply::getApproverId, null)
            .set(ReceivedApply::getApproveTime, null)
            .set(ReceivedApply::getCurrentNode, null);
        return receivedApplyMapper.update(null, update);
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
