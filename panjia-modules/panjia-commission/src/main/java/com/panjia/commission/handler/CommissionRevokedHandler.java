package com.panjia.commission.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.commission.domain.ApplicationStatus;
import com.panjia.commission.domain.CommissionApplication;
import com.panjia.commission.domain.CommissionItem;
import com.panjia.commission.mapper.CommissionApplicationMapper;
import com.panjia.commission.mapper.CommissionItemMapper;
import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.ImportBatchRevokedEvent;
import com.panjia.contracts.port.ApprovalPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

/**
 * 导入批次撤销处理器（结佣域段）。
 * <p>
 * 两类清理语义：
 * <ul>
 *   <li><b>HISTORY_PAYROLL</b>：删除期间内导入产生的 LOCKED 申请单（无流程实例）
 *       及其全部明细（同老导入器撤销清理第①段）；有流程实例的正常申请单不受影响。</li>
 *   <li><b>KE_SIGNED / KE_RECEIVED</b>（2026-10-30 硬删口径）：按事件合同集合删除
 *       <b>自动发起</b>（{@code auto_initiated=true}）且<b>尚无审批人办理</b>的 DRAFT/SUBMITTED 结佣单及明细。
 *       这类单是实收审批通过事件触发的导入连锁产物，属导入错误数据，撤销后必然重新导入，
 *       故直接物理删除（不为留痕保留 CANCELLED 单）。删除前先撤流程实例，避免 flow_* 孤儿。
 *       审批人已办理、已审批锁定的单说明已被人工处理，保留不动；人工发起的单不在删除范围
 *       （批次撤销前置校验 {@code CommissionConsumptionQueryPort} 已确保其存在时不放行）。</li>
 * </ul>
 * 幂等：重复消费不会报错（删了再删影响 0 行）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommissionRevokedHandler implements DomainEventHandler {

    private static final String SOURCE_TYPE_KE_SIGNED = "KE_SIGNED";
    private static final String SOURCE_TYPE_KE_RECEIVED = "KE_RECEIVED";
    private static final String SOURCE_TYPE_HISTORY_PAYROLL = "HISTORY_PAYROLL";

    private final CommissionApplicationMapper applicationMapper;
    private final CommissionItemMapper itemMapper;
    private final ApprovalPort approvalPort;
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
            log.error("[结佣-批次撤销] 撤销事件反序列化失败：eventId={}", eventId, e);
            return;
        }
        if (StringUtils.isBlank(event.getPeriod())) {
            return;
        }
        String period = event.getPeriod().trim();
        if (SOURCE_TYPE_HISTORY_PAYROLL.equals(event.getSourceType())) {
            cleanupHistoryPayroll(period);
        } else if (SOURCE_TYPE_KE_SIGNED.equals(event.getSourceType())
            || SOURCE_TYPE_KE_RECEIVED.equals(event.getSourceType())) {
            cleanupAutoApplications(event);
        }
    }

    /**
     * 历史工资批次撤销：清理期间 LOCKED 且无流程实例的申请单及其明细。
     */
    private void cleanupHistoryPayroll(String period) {
        List<CommissionApplication> apps = applicationMapper.selectList(
            new LambdaQueryWrapper<CommissionApplication>()
                .eq(CommissionApplication::getPeriod, period)
                .eq(CommissionApplication::getStatus, ApplicationStatus.LOCKED)
                .isNull(CommissionApplication::getProcessInstanceId));
        int items = 0;
        for (CommissionApplication app : apps) {
            items += itemMapper.delete(new LambdaQueryWrapper<CommissionItem>()
                .eq(CommissionItem::getApplicationId, app.getId()));
            applicationMapper.deleteById(app.getId());
        }
        log.info("[结佣-历史撤销] 清理完成：period={}, 申请单={}, 明细={}", period, apps.size(), items);
    }

    /**
     * 贝壳新签/实收批次撤销：硬删事件合同集合内<b>自动发起</b>的结佣单及其明细。
     * <p>自动发起（{@code auto_initiated=true}）是实收审批通过事件的连锁产物，属于导入错误数据，
     * 撤销批次后必然重新导入，保留 CANCELLED 留痕没有意义，故直接物理删除。
     * <p>删除范围：{@code DRAFT}/{@code SUBMITTED} 且<b>尚无审批人办理</b>（总监/财务节点）的自动单；
     * 一旦审批人办理过、或已审批锁定，说明已被人工处理，保留不动（符合预期）。
     * <p>不按期间过滤：自动发起单的期间 = 实收月，可能晚于导入批次归属月。
     * <p>删除前先撤流程实例（{@code cancelBatch} 对无实例的 DRAFT 幂等容忍），避免 flow_* 孤儿。
     * <p>合同号由导入域从本批次及被恢复旧批次的归一化记录汇总后随事件携带，
     * 不依赖本域跨表反查批次数据。
     */
    private void cleanupAutoApplications(ImportBatchRevokedEvent event) {
        List<String> contractNos = event.getContractNos();
        if (contractNos == null || contractNos.isEmpty()) {
            log.info("[结佣-批次撤销] 事件无合同号，跳过自动结佣单清理：batchId={}", event.getBatchId());
            return;
        }
        List<CommissionApplication> autoApps = applicationMapper.selectList(
            new LambdaQueryWrapper<CommissionApplication>()
                // 事件集合混装合同号与订单号（一手房/家装荐客等无合同号业务以订单号为锚点），双键兜底
                .and(w -> w.in(CommissionApplication::getContractNo, contractNos)
                    .or().in(CommissionApplication::getOrderNo, contractNos))
                .eq(CommissionApplication::getAutoInitiated, Boolean.TRUE)
                .in(CommissionApplication::getStatus,
                    ApplicationStatus.DRAFT, ApplicationStatus.SUBMITTED));
        if (autoApps.isEmpty()) {
            log.info("[结佣-批次撤销] 无自动结佣单需清理：batchId={}, contracts={}",
                event.getBatchId(), contractNos.size());
            return;
        }
        List<Long> appIds = autoApps.stream().map(CommissionApplication::getId).toList();
        Set<Long> touched;
        try {
            touched = approvalPort.findApproverTouchedBizIds(appIds);
        } catch (Exception e) {
            // 审批痕迹查询失败：保守保留，不硬删（宁可不删也不误删人工处理过的单）
            log.warn("[结佣-批次撤销] 审批痕迹查询失败，跳过自动结佣单清理：batchId={}, error={}",
                event.getBatchId(), e.getMessage());
            return;
        }
        List<Long> deletableIds = autoApps.stream()
            .filter(a -> !touched.contains(a.getId()))
            .map(CommissionApplication::getId)
            .toList();
        if (deletableIds.isEmpty()) {
            log.info("[结佣-批次撤销] 自动结佣单均已被审批人办理，保留：batchId={}, count={}",
                event.getBatchId(), autoApps.size());
            return;
        }
        // 先撤流程实例（DRAFT 无实例，deleteInstanceSys 幂等容忍），避免待办出现幽灵任务
        approvalPort.cancelBatch(deletableIds);
        int items = itemMapper.delete(new LambdaQueryWrapper<CommissionItem>()
            .in(CommissionItem::getApplicationId, deletableIds));
        int deleted = applicationMapper.deleteByIds(deletableIds);
        log.info("[结佣-批次撤销] 自动结佣单已硬删：batchId={}, 申请单={}, 明细={}, 保留(已办理)={}",
            event.getBatchId(), deleted, items, autoApps.size() - deletableIds.size());
    }
}
