package com.panjia.received.listener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.EventPort;
import com.panjia.contracts.event.ImportBatchRevokedEvent;
import com.panjia.contracts.event.ReceivedApprovedEvent;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.received.domain.ReceivedContract;
import com.panjia.received.domain.ReceivedDetail;
import com.panjia.performance.domain.ReceivedApply;
import com.panjia.performance.domain.ReceivedApplyStatus;
import com.panjia.received.mapper.ReceivedContractMapper;
import com.panjia.received.mapper.ReceivedDetailMapper;
import com.panjia.performance.mapper.ReceivedApplyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导入批次撤销事件处理器（实收域）。
 * <p>
 * KE_RECEIVED / HISTORY_PAYROLL 批次撤销：
 * <ol>
 *   <li>硬删实收明细 + 实收合同；关联实收审批单按「纯本批单 / 合并单」分流：
 *     <ul>
 *       <li><b>纯本批单</b>（单内 ACTIVE 明细全部来自本批）：SUBMITTED 等持有流程
 *           实例的，先经 ApprovalPort 系统级作废流程（撤销校验已保证仅系统自动
 *           发起、无审批人办理的单才会走到这里），再硬删单据；自动通过 APPROVED
 *           单无流程实例直接删；</li>
 *       <li><b>合并单</b>（明细曾并入其他批次首建的审批单，或本批明细与他批明细
 *           共用一单）：单据与流程保留，摘除本批明细后按剩余 ACTIVE 明细重算
 *           条数与金额，审批继续基于纠正后数据进行。</li>
 *     </ul>
 *   </li>
 *   <li><b>保底恢复（2026-10-02）</b>：restoredBatchIds 非空时，把被冲销旧批次的
 *       实收明细 SUPERSEDED→ACTIVE；旧批次归档时其审批单被新批次合并/沿用，
 *       恢复后按当前 ACTIVE 明细重算单据条数与金额，并对 APPROVED 单重放实收通过
 *       事件，结佣域据此重建 DRAFT 草稿单。</li>
 * </ol>
 * <p>
 * 幂等：重复消费安全（删除/状态翻转幂等；事件重放由结佣建单活跃单判重兜底）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReceivedRevokedHandler implements DomainEventHandler {

    private static final String SOURCE_TYPE_KE_RECEIVED = "KE_RECEIVED";
    private static final String SOURCE_TYPE_HISTORY_PAYROLL = "HISTORY_PAYROLL";

    /** 明细有效状态（与 ImportToReceivedServiceImpl 常量口径一致） */
    private static final String DETAIL_STATUS_ACTIVE = "ACTIVE";
    private static final String DETAIL_STATUS_SUPERSEDED = "SUPERSEDED";

    private final ReceivedContractMapper contractMapper;
    private final ReceivedDetailMapper detailMapper;
    private final ReceivedApplyMapper applyMapper;
    /**
     * 审批端口：撤销自动发起的审批中单时系统级作废流程实例，避免 flow_* 残留。
     */
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
            log.error("[实收撤销] 事件反序列化失败：eventId={}", eventId, e);
            return;
        }

        String sourceType = event.getSourceType();
        if (sourceType == null) {
            return;
        }
        if (!SOURCE_TYPE_KE_RECEIVED.equals(sourceType) && !SOURCE_TYPE_HISTORY_PAYROLL.equals(sourceType)) {
            return;
        }

        Long batchId = event.getBatchId();
        List<Long> restoredBatchIds = parseIdList(event.getRestoredBatchIds());
        log.info("[实收撤销] 开始清理：batchId={}, sourceType={}, restoredBatchIds={}",
            batchId, sourceType, restoredBatchIds);

        // 0. 删明细前快照：本批全部明细绑定了哪些审批单（含 SUPERSEDED 行的旧绑定）
        List<ReceivedDetail> batchDetails = detailMapper.selectList(
            new LambdaQueryWrapper<ReceivedDetail>()
                .select(ReceivedDetail::getId, ReceivedDetail::getReceivedApplyId,
                    ReceivedDetail::getDetailStatus)
                .eq(ReceivedDetail::getSourceBatchId, batchId));
        Set<Long> affectedApplyIds = new LinkedHashSet<>();
        // 本批 ACTIVE 明细按单计数（纯本批单判定用）
        Map<Long, long[]> curActiveCountByApply = new LinkedHashMap<>();
        for (ReceivedDetail d : batchDetails) {
            Long applyId = d.getReceivedApplyId();
            if (applyId == null) {
                continue;
            }
            affectedApplyIds.add(applyId);
            if (DETAIL_STATUS_ACTIVE.equals(d.getDetailStatus())) {
                curActiveCountByApply.computeIfAbsent(applyId, k -> new long[]{0})[0]++;
            }
        }

        // 纯本批单 / 合并单分流（一次查全部关联单的 ACTIVE 明细，内存分组）
        Set<Long> pureBatchApplyIds = new LinkedHashSet<>();
        Set<Long> mergedApplyIds = new LinkedHashSet<>();
        if (!affectedApplyIds.isEmpty()) {
            classifyAffectedApplies(affectedApplyIds, curActiveCountByApply,
                pureBatchApplyIds, mergedApplyIds);
        }

        // 1. 纯本批单：先作废流程实例（自动发起、无人审批，撤销校验已拦截其他情形），再随批删单
        if (!pureBatchApplyIds.isEmpty()) {
            List<Long> workflowBizIds = applyMapper.selectList(
                    new LambdaQueryWrapper<ReceivedApply>()
                        .select(ReceivedApply::getId)
                        .in(ReceivedApply::getId, pureBatchApplyIds)
                        .isNotNull(ReceivedApply::getProcessInstanceId))
                .stream().map(ReceivedApply::getId).toList();
            if (!workflowBizIds.isEmpty()) {
                approvalPort.cancelBatch(workflowBizIds);
                log.info("[实收撤销] 纯本批审批单流程实例已作废：batchId={}, count={}",
                    batchId, workflowBizIds.size());
            }
        }

        // 2. 硬删实收明细
        int details = detailMapper.delete(new LambdaQueryWrapper<ReceivedDetail>()
            .eq(ReceivedDetail::getSourceBatchId, batchId));
        log.info("[实收撤销] 明细已删：batchId={}, count={}", batchId, details);

        // 3. 硬删实收合同
        int contracts = contractMapper.delete(new LambdaQueryWrapper<ReceivedContract>()
            .eq(ReceivedContract::getBatchId, batchId));
        log.info("[实收撤销] 合同已删：batchId={}, count={}", batchId, contracts);

        // 4. 审批单处置
        if (!pureBatchApplyIds.isEmpty()) {
            int deleted = applyMapper.deleteByIds(pureBatchApplyIds);
            log.info("[实收撤销] 纯本批审批单已删：batchId={}, count={}", batchId, deleted);
        }
        if (!mergedApplyIds.isEmpty()) {
            // 合并单：本批明细摘除后按剩余 ACTIVE 明细重算，单据与审批流保留
            List<ReceivedApply> recalculated = recalculateApplies(new ArrayList<>(mergedApplyIds));
            log.info("[实收撤销] 合并审批单已重算保留：batchId={}, count={}", batchId, recalculated.size());
        }

        // 5. 保底恢复：旧批次明细翻回 ACTIVE，重算沿用单并重放实收通过事件
        if (!restoredBatchIds.isEmpty()) {
            int restored = detailMapper.update(null, new LambdaUpdateWrapper<ReceivedDetail>()
                .in(ReceivedDetail::getSourceBatchId, restoredBatchIds)
                .eq(ReceivedDetail::getDetailStatus, DETAIL_STATUS_SUPERSEDED)
                .set(ReceivedDetail::getDetailStatus, DETAIL_STATUS_ACTIVE));
            log.info("[实收撤销] 旧批次明细已恢复：restoredBatchIds={}, count={}", restoredBatchIds, restored);

            Set<Long> restoredApplyIds = detailMapper.selectList(
                    new LambdaQueryWrapper<ReceivedDetail>()
                        .select(ReceivedDetail::getReceivedApplyId)
                        .in(ReceivedDetail::getSourceBatchId, restoredBatchIds)
                        .eq(ReceivedDetail::getDetailStatus, DETAIL_STATUS_ACTIVE)
                        .isNotNull(ReceivedDetail::getReceivedApplyId))
                .stream().map(ReceivedDetail::getReceivedApplyId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            // 已在合并分流中删除的纯本批单不参与（其 ID 不会出现在存活单中，重算内部会跳过空单）
            List<ReceivedApply> recalculated = recalculateApplies(new ArrayList<>(restoredApplyIds));
            replayApprovedApplies(recalculated, event.getOperatorId());
        }
    }

    /**
     * 按「单内剩余 ACTIVE 明细是否全部来自本批」把关联单分为纯本批单 / 合并单。
     * <p>一次 IN 查询拉全部关联单的 ACTIVE 明细内存分组，避免逐单 COUNT。
     * 关联到本批但已无 ACTIVE 明细的单（本批行均为 SUPERSEDED），其 ACTIVE 明细
     * 属于他批，按合并单处理（重算后无变化），绝不误删。
     *
     * @param affectedApplyIds      本批明细绑定过的全部审批单 ID
     * @param curActiveCountByApply 本批 ACTIVE 明细按单计数
     * @param pureSink              纯本批单结果容器
     * @param mergedSink            合并单结果容器
     */
    private void classifyAffectedApplies(Set<Long> affectedApplyIds,
                                         Map<Long, long[]> curActiveCountByApply,
                                         Set<Long> pureSink, Set<Long> mergedSink) {
        List<ReceivedDetail> allActiveDetails = detailMapper.selectList(
            new LambdaQueryWrapper<ReceivedDetail>()
                .select(ReceivedDetail::getId, ReceivedDetail::getReceivedApplyId)
                .in(ReceivedDetail::getReceivedApplyId, affectedApplyIds)
                .eq(ReceivedDetail::getDetailStatus, DETAIL_STATUS_ACTIVE));
        Map<Long, long[]> totalActiveCountByApply = new LinkedHashMap<>();
        for (ReceivedDetail d : allActiveDetails) {
            totalActiveCountByApply.computeIfAbsent(d.getReceivedApplyId(), k -> new long[]{0})[0]++;
        }
        for (Long applyId : affectedApplyIds) {
            long total = totalActiveCountByApply.getOrDefault(applyId, new long[]{0})[0];
            long cur = curActiveCountByApply.getOrDefault(applyId, new long[]{0})[0];
            // 无 ACTIVE 明细（空单或本批行全 SUPERSEDED）：total=0,cur=0 → 纯本批（空单随批清理）
            if (total == cur) {
                pureSink.add(applyId);
            } else {
                mergedSink.add(applyId);
            }
        }
    }

    /**
     * 按当前绑定的 ACTIVE 明细重算实收审批单的条数与金额。
     * <p>用于两处：撤销时合并单摘除本批明细后的收口；旧批次恢复后沿用单的重算。
     * 已不存在的单据（纯本批单已删）自动跳过。
     *
     * @param applyIds 待重算单据 ID
     * @return 实际重算的审批单列表（供重放实收通过事件）
     */
    private List<ReceivedApply> recalculateApplies(List<Long> applyIds) {
        if (applyIds == null || applyIds.isEmpty()) {
            return List.of();
        }
        List<ReceivedDetail> activeDetails = detailMapper.selectList(
            new LambdaQueryWrapper<ReceivedDetail>()
                .in(ReceivedDetail::getReceivedApplyId, applyIds)
                .eq(ReceivedDetail::getDetailStatus, DETAIL_STATUS_ACTIVE));
        if (activeDetails.isEmpty()) {
            return List.of();
        }
        // 按审批单聚合：条数 + 金额合计（一次查询内存分组，避免逐单 COUNT/SUM）
        Map<Long, long[]> countHolder = new LinkedHashMap<>();
        Map<Long, BigDecimal> sumHolder = new LinkedHashMap<>();
        for (ReceivedDetail d : activeDetails) {
            Long applyId = d.getReceivedApplyId();
            countHolder.computeIfAbsent(applyId, k -> new long[]{0})[0]++;
            BigDecimal amount = d.getPerformanceAmount() == null ? BigDecimal.ZERO : d.getPerformanceAmount();
            sumHolder.merge(applyId, amount, BigDecimal::add);
        }
        List<ReceivedApply> result = new ArrayList<>(countHolder.size());
        for (Map.Entry<Long, long[]> entry : countHolder.entrySet()) {
            Long applyId = entry.getKey();
            ReceivedApply apply = applyMapper.selectById(applyId);
            if (apply == null) {
                continue;
            }
            int itemCount = (int) entry.getValue()[0];
            BigDecimal totalAmount = sumHolder.getOrDefault(applyId, BigDecimal.ZERO);
            apply.setItemCount(itemCount);
            apply.setReceivedAmount(totalAmount);
            applyMapper.updateById(apply);
            result.add(apply);
        }
        log.info("[实收撤销] 审批单已按存活 ACTIVE 明细重算：applyCount={}", result.size());
        return result;
    }

    /**
     * 对重算后仍为 APPROVED 的实收单重放实收通过事件（结佣域重建 DRAFT 草稿单）。
     * <p>DRAFT 单（旧批次时期就在等新签）不重放——createApplicationWithItems 会因
     * 实收未通过拒绝建单，等新签导入触发 resolveDraftAfterNewSign 自然流转。
     */
    private void replayApprovedApplies(List<ReceivedApply> applies, Long operatorId) {
        int replayed = 0;
        for (ReceivedApply apply : applies) {
            if (apply.getStatus() != ReceivedApplyStatus.APPROVED) {
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
        if (replayed > 0) {
            log.info("[实收撤销] 实收通过事件重放：count={}", replayed);
        }
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
                log.warn("[实收撤销] 非法 restoredBatchId 已忽略：{}", id);
            }
        }
        return result;
    }
}
