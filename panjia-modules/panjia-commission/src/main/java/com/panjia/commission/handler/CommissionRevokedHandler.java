package com.panjia.commission.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.commission.domain.ApplicationStatus;
import com.panjia.commission.domain.CommissionApplication;
import com.panjia.commission.domain.CommissionItem;
import com.panjia.commission.mapper.CommissionApplicationMapper;
import com.panjia.commission.mapper.CommissionItemMapper;
import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.ImportBatchRevokedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 导入批次撤销处理器（结佣域段）。
 * <p>
 * 两类清理语义：
 * <ul>
 *   <li><b>HISTORY_PAYROLL</b>：删除期间内导入产生的 LOCKED 申请单（无流程实例）
 *       及其全部明细（同老导入器撤销清理第①段）；有流程实例的正常申请单不受影响。</li>
 *   <li><b>KE_SIGNED / KE_RECEIVED</b>（2026-10-02 保底撤销）：删除事件合同集合内
 *       实收自动通过时产生的 DRAFT 草稿单（未提交、无流程实例）及明细。此类草稿单
 *       是导入的连锁产物，随误导入批次一并清除；旧批次恢复后由重放的实收通过事件
 *       重建。前置校验已保证不存在提交/审批/锁定/驳回单，此处仅删除 DRAFT 双保险。</li>
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
            cleanupAutoDrafts(event, period);
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
     * 贝壳新签/实收批次撤销：删除事件合同集合内自动产生的 DRAFT 草稿单（无流程实例）。
     * <p>合同号由导入域从本批次及被恢复旧批次的归一化记录汇总后随事件携带，
     * 不依赖本域跨表反查批次数据。
     */
    private void cleanupAutoDrafts(ImportBatchRevokedEvent event, String period) {
        List<String> contractNos = event.getContractNos();
        if (contractNos == null || contractNos.isEmpty()) {
            log.info("[结佣-批次撤销] 事件无合同号，跳过草稿清理：batchId={}, period={}",
                event.getBatchId(), period);
            return;
        }
        List<CommissionApplication> drafts = applicationMapper.selectList(
            new LambdaQueryWrapper<CommissionApplication>()
                .eq(CommissionApplication::getPeriod, period)
                .in(CommissionApplication::getContractNo, contractNos)
                .eq(CommissionApplication::getStatus, ApplicationStatus.DRAFT)
                .isNull(CommissionApplication::getProcessInstanceId));
        if (drafts.isEmpty()) {
            log.info("[结佣-批次撤销] 无自动草稿单需清理：batchId={}, period={}, contracts={}",
                event.getBatchId(), period, contractNos.size());
            return;
        }
        int items = 0;
        for (CommissionApplication draft : drafts) {
            items += itemMapper.delete(new LambdaQueryWrapper<CommissionItem>()
                .eq(CommissionItem::getApplicationId, draft.getId()));
            applicationMapper.deleteById(draft.getId());
        }
        log.info("[结佣-批次撤销] 自动草稿单已清理：batchId={}, period={}, 申请单={}, 明细={}",
            event.getBatchId(), period, drafts.size(), items);
    }
}
