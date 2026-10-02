package com.panjia.performance.adapter;

import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.BatchConsumptionQueryPort;
import com.panjia.contracts.port.CommissionConsumptionQueryPort;
import com.panjia.contracts.port.ReceivedConsumptionQueryPort;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceAdjust;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.ReceivedApply;
import com.panjia.performance.domain.ReceivedApplyStatus;
import com.panjia.performance.mapper.PerformanceAdjustMapper;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.mapper.ReceivedApplyMapper;
import com.panjia.performance.service.IPeriodCloseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批次消费状态查询端口实现（入站适配器）。
 * <p>
 * 定义在 panjia-contracts，由业绩域实现，供导入域撤销批次前调用。
 * 校验不可撤销条件（2026-10-02 保底撤销口径）：
 * <ol>
 *   <li>期间已封账；</li>
 *   <li>存在业绩调整单（一创建即走流程，硬删会产生流程孤儿）；</li>
 *   <li>实收确认单存在人工流程动作：
 *     <ul>
 *       <li>人工终审通过 APPROVED 且持有流程实例——拦截；自动通过直建的 APPROVED
 *           单（无流程实例，贝壳实收导入“到账≥新签”系统直批）放行随批清理；</li>
 *       <li>审批中 SUBMITTED 且持有流程实例：以工作流历史为准，<b>已有审批人
 *           （财务/总监等角色节点）办理过</b>的拦截；系统导入时自动发起、尚无任何
 *           审批人触碰的（仅留开始/申请人节点记录）放行，撤销时级联作废流程；</li>
 *     </ul>
 *   </li>
 *   <li>候选单三来源合并去重：批次直挂（apply.batch_id）、新签批次按合同/订单
 *       反查的下游单、本批实收合同按合同/订单反查的合并单（明细并入他批首建单
 *       时 apply.batch_id 不等于本批）；</li>
 *   <li>结佣域已消费：存在提交/审批/锁定（有流程实例）/驳回的结佣申请单，
 *       或存在未作废结佣调整单（含已执行联动：本批实收明细带结佣调整痕迹
 *       adjust_id 非空）。实收自动通过时产生的 DRAFT 结佣草稿（未提交、
 *       无流程实例）不算已结佣，允许撤销，随批删除并在旧批次恢复后重建。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchConsumptionQueryAdapter implements BatchConsumptionQueryPort {

    private final IPeriodCloseService periodCloseService;
    private final PerformanceFactMapper factMapper;
    private final PerformanceAdjustMapper adjustMapper;
    private final ReceivedApplyMapper receivedApplyMapper;
    /**
     * 审批端口：查 SUBMITTED 单是否已被审批人（非发起人节点）办理。
     */
    private final ApprovalPort approvalPort;
    /**
     * 结佣消费校验端口（实现在 panjia-commission）。
     * 降级策略：端口缺失或异常时保守拒绝（宁可不撤也不误撤）。
     */
    private final ObjectProvider<CommissionConsumptionQueryPort> commissionQueryPortProvider;
    /**
     * 实收消费校验端口（实现在 panjia-received）。
     * KE_RECEIVED 批次在业绩表无事实，本批实收合同范围/明细调整痕迹需向实收域反查。
     * 降级策略：端口缺失或异常时保守拒绝（宁可不撤也不误撤）。
     */
    private final ObjectProvider<ReceivedConsumptionQueryPort> receivedQueryPortProvider;

    @Override
    public RevokeCheckResult checkRevocable(Long batchId, String period) {
        // 校验①：期间已封账
        if (period != null && periodCloseService.isClosed(period)) {
            return RevokeCheckResult.reject("期间[" + period + "]已封账，禁止撤销");
        }

        // 查该批次全部事实（校验②④与合同集合复用）
        List<PerformanceFact> facts = factMapper.selectList(
            new LambdaQueryWrapper<PerformanceFact>()
                .eq(PerformanceFact::getBatchId, batchId));
        List<Long> factIds = facts.stream().map(PerformanceFact::getId).toList();

        // 校验②：存在业绩调整单（一创建即走流程，硬删会产生流程孤儿）
        // adjust → fact → batch，通过 fact_id 关联
        if (!factIds.isEmpty()) {
            Long adjustCount = adjustMapper.selectCount(
                new LambdaQueryWrapper<PerformanceAdjust>()
                    .in(PerformanceAdjust::getFactId, factIds));
            if (adjustCount != null && adjustCount > 0) {
                return RevokeCheckResult.reject(
                    "该批次存在业绩调整单（" + adjustCount + " 条），禁止撤销，请先处理调整单");
            }
        }

        // 本批新签业务键（新签反查下游实收单用）
        Set<String> expectOrderNos = new LinkedHashSet<>();
        Set<String> expectContractNos = new LinkedHashSet<>();
        for (PerformanceFact f : facts) {
            if (!FactType.PERF_EXPECT.equals(f.getFactType())) {
                continue;
            }
            if (f.getOrderNo() != null && !f.getOrderNo().isEmpty()) {
                expectOrderNos.add(f.getOrderNo());
            }
            if (f.getContractNo() != null && !f.getContractNo().isEmpty()) {
                expectContractNos.add(f.getContractNo());
            }
        }

        // 提前加载实收侧范围（KE_RECEIVED 批次事实在实收域；同时兜底合并单与结佣校验）
        ReceivedConsumptionQueryPort receivedPort = receivedQueryPortProvider.getIfAvailable();
        if (receivedPort == null) {
            log.warn("[导入撤销] ReceivedConsumptionQueryPort 未实现，保守拒绝：batchId={}", batchId);
            return RevokeCheckResult.reject("实收消费状态校验不可用，无法撤销");
        }
        ReceivedConsumptionQueryPort.BatchReceivedScope receivedScope;
        try {
            receivedScope = receivedPort.loadBatchScope(batchId);
            // 结佣调整执行时 supersede 实收明细（adjust_id 非空，含调整后新生效行）；
            // 旧口径靠「实收单 APPROVED 全拦」间接挡住，自动通过放行后必须显式拦截
            long adjustedDetails = receivedPort.countAdjustedDetails(batchId);
            if (adjustedDetails > 0) {
                return RevokeCheckResult.reject(
                    "该批次实收明细存在结佣调整记录（" + adjustedDetails
                        + " 条），禁止撤销，请先作废相关结佣调整单");
            }
        } catch (Exception e) {
            log.warn("[导入撤销] 实收消费校验异常，保守拒绝：batchId={}, error={}", batchId, e.getMessage());
            return RevokeCheckResult.reject("实收消费状态校验失败，无法撤销");
        }

        // 校验③：汇总本批关联的全部 SUBMITTED/APPROVED 实收单（三来源去重）
        Map<Long, ReceivedApply> relatedApplies = new LinkedHashMap<>();
        // 来源 A：批次直挂（apply.batch_id = 本批）
        loadAppliesByBatch(batchId, relatedApplies);
        // 来源 B：新签反查下游实收单（新签可能已触发自动/人工实收流程）
        if (!expectOrderNos.isEmpty() || !expectContractNos.isEmpty()) {
            loadAppliesByBizKeys(null, expectContractNos, expectOrderNos, relatedApplies);
        }
        // 来源 C：本批实收合同反查（明细可能合并进其他批次首建的审批单）
        if (receivedScope != null
            && (!receivedScope.getContractNos().isEmpty() || !receivedScope.getOrderNos().isEmpty())) {
            loadAppliesByBizKeys(period,
                new LinkedHashSet<>(receivedScope.getContractNos()),
                new LinkedHashSet<>(receivedScope.getOrderNos()),
                relatedApplies);
        }

        // 人工终审通过且持有流程实例：拦截（自动通过单无流程实例，不在此列）
        long manualApproved = relatedApplies.values().stream()
            .filter(a -> a.getStatus() == ReceivedApplyStatus.APPROVED
                && a.getProcessInstanceId() != null && !a.getProcessInstanceId().isBlank())
            .count();
        if (manualApproved > 0) {
            return RevokeCheckResult.reject(
                "该批次存在人工审批通过的实收确认单（" + manualApproved
                    + " 条），禁止撤销；系统自动通过的单据可随批撤销");
        }

        // 审批中且持有流程实例：以工作流历史区分「系统自动发起、无人审批」与「已有审批人办理」
        List<Long> submittingIds = relatedApplies.values().stream()
            .filter(a -> a.getStatus() == ReceivedApplyStatus.SUBMITTED
                && a.getProcessInstanceId() != null && !a.getProcessInstanceId().isBlank())
            .map(ReceivedApply::getId)
            .toList();
        if (!submittingIds.isEmpty()) {
            Set<Long> touched;
            try {
                touched = approvalPort.findApproverTouchedBizIds(submittingIds);
            } catch (Exception e) {
                log.warn("[导入撤销] 审批痕迹查询异常，保守拒绝：batchId={}, error={}", batchId, e.getMessage());
                return RevokeCheckResult.reject("实收审批状态校验失败，无法撤销");
            }
            long untouched = submittingIds.size() - touched.size();
            if (!touched.isEmpty()) {
                return RevokeCheckResult.reject(
                    "该批次关联的实收确认单已有审批人办理（" + touched.size()
                        + " 条），禁止撤销，请先走驳回/作废流程；"
                        + "其余系统自动发起且无人审批的单据（" + untouched + " 条）本可随批撤销");
            }
            log.info("[导入撤销] 审批中单均为系统自动发起且无审批人办理，随批作废：batchId={}, count={}",
                batchId, submittingIds.size());
        }

        // 校验⑤：结佣消费状态
        // 合同集合：本批新签事实 + 本批实收合同 + 关联实收审批单（三来源合并，覆盖合并单）
        Set<String> commissionContracts = new LinkedHashSet<>(expectContractNos);
        Set<String> commissionOrders = new LinkedHashSet<>(expectOrderNos);
        if (receivedScope != null) {
            commissionContracts.addAll(receivedScope.getContractNos());
            commissionOrders.addAll(receivedScope.getOrderNos());
        }
        for (ReceivedApply apply : relatedApplies.values()) {
            if (apply.getContractNo() != null && !apply.getContractNo().isBlank()) {
                commissionContracts.add(apply.getContractNo());
            }
            if (apply.getOrderNo() != null && !apply.getOrderNo().isBlank()) {
                commissionOrders.add(apply.getOrderNo());
            }
        }
        CommissionConsumptionQueryPort commissionPort = commissionQueryPortProvider.getIfAvailable();
        if (commissionPort == null) {
            log.warn("[导入撤销] CommissionConsumptionQueryPort 未实现，保守拒绝：batchId={}", batchId);
            return RevokeCheckResult.reject("结佣消费状态校验不可用，无法撤销");
        }
        try {
            CommissionConsumptionQueryPort.RevokeCheckResult commissionResult =
                commissionPort.checkRevocable(period,
                    new ArrayList<>(commissionContracts), new ArrayList<>(commissionOrders));
            if (!commissionResult.isRevocable()) {
                return RevokeCheckResult.reject(commissionResult.getReason());
            }
        } catch (Exception e) {
            log.warn("[导入撤销] 结佣消费校验异常，保守拒绝：batchId={}, error={}", batchId, e.getMessage());
            return RevokeCheckResult.reject("结佣消费状态校验失败，无法撤销");
        }

        return RevokeCheckResult.ok();
    }

    /**
     * 按批次直挂查 SUBMITTED/APPROVED 实收单并入结果 Map（按 ID 去重）。
     */
    private void loadAppliesByBatch(Long batchId, Map<Long, ReceivedApply> sink) {
        receivedApplyMapper.selectList(
                new LambdaQueryWrapper<ReceivedApply>()
                    .eq(ReceivedApply::getBatchId, batchId)
                    .in(ReceivedApply::getStatus,
                        ReceivedApplyStatus.SUBMITTED, ReceivedApplyStatus.APPROVED))
            .forEach(a -> sink.putIfAbsent(a.getId(), a));
    }

    /**
     * 按合同号/订单号双键查 SUBMITTED/APPROVED 实收单并入结果 Map（按 ID 去重）。
     *
     * @param period      归属月过滤（null 表示不限月，新签反查下游时跨月实收也要命中）
     * @param contractNos 合同号集合
     * @param orderNos    订单号集合
     * @param sink        去重结果容器
     */
    private void loadAppliesByBizKeys(String period, Set<String> contractNos, Set<String> orderNos,
                                      Map<Long, ReceivedApply> sink) {
        if ((contractNos == null || contractNos.isEmpty()) && (orderNos == null || orderNos.isEmpty())) {
            return;
        }
        LambdaQueryWrapper<ReceivedApply> query = new LambdaQueryWrapper<ReceivedApply>()
            .in(ReceivedApply::getStatus,
                ReceivedApplyStatus.SUBMITTED, ReceivedApplyStatus.APPROVED);
        if (period != null) {
            query.eq(ReceivedApply::getPeriod, period);
        }
        boolean hasContracts = contractNos != null && !contractNos.isEmpty();
        boolean hasOrders = orderNos != null && !orderNos.isEmpty();
        if (hasContracts && hasOrders) {
            query.and(w -> w.in(ReceivedApply::getContractNo, contractNos)
                .or().in(ReceivedApply::getOrderNo, orderNos));
        } else if (hasContracts) {
            query.in(ReceivedApply::getContractNo, contractNos);
        } else {
            query.in(ReceivedApply::getOrderNo, orderNos);
        }
        List<ReceivedApply> applies = receivedApplyMapper.selectList(query);
        for (ReceivedApply apply : applies) {
            // 双键 OR 查询可能误命中（合同号恰好等于另一单订单号），内存再精确校验一次
            if (matchesAny(apply.getContractNo(), contractNos)
                || matchesAny(apply.getOrderNo(), orderNos)) {
                sink.putIfAbsent(apply.getId(), apply);
            }
        }
    }

    /**
     * 值命中候选集合（空白不参与）。
     */
    private boolean matchesAny(String value, Collection<String> candidates) {
        if (value == null || value.isBlank() || candidates == null || candidates.isEmpty()) {
            return false;
        }
        return candidates.contains(value);
    }
}
