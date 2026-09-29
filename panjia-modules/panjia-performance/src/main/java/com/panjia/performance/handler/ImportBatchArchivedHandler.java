package com.panjia.performance.handler;

import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.ImportBatchArchivedEvent;
import com.panjia.contracts.port.ImportToReceivedPort;
import com.panjia.contracts.port.ReceivedApplyPort;
import com.panjia.performance.domain.PerformanceConsumeLog;
import com.panjia.performance.service.PerformanceEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;

/**
 * 导入批次归档事件处理器（DomainEventHandler，panjia-contracts Port 实现）。
 * <p>
 * 拆表后三路分发：
 * <ul>
 *   <li>KE_SIGNED（贝壳新签）→ PerformanceEngine.buildFromBatch()，只建 PERF_EXPECT 新签事实</li>
 *   <li>KE_RECEIVED（贝壳实收）→ ImportToReceivedPort.consumeBatch()，直接写实收表</li>
 *   <li>HISTORY_PAYROLL（历史工资）→ ImportToReceivedPort.consumeBatch()，直接写实收表 + 实收审批直建</li>
 * </ul>
 * EMPLOYEE / ATTENDANCE / POINTS / OTHERS 等来源直接忽略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportBatchArchivedHandler implements DomainEventHandler {

    /** 贝壳新签导入：走 PerformanceEngine 建 PERF_EXPECT */
    private static final String SOURCE_TYPE_KE_SIGNED = "KE_SIGNED";
    /** 贝壳实收导入：走 ImportToReceivedPort 写实收表 */
    private static final String SOURCE_TYPE_KE_RECEIVED = "KE_RECEIVED";
    /** 历史工资导入：走 ImportToReceivedPort 写实收表 + 实收审批直建 */
    private static final String SOURCE_TYPE_HISTORY_PAYROLL = "HISTORY_PAYROLL";
    /** 需要处理的来源类型集合 */
    private static final java.util.Set<String> HANDLED_SOURCE_TYPES =
        java.util.Set.of(SOURCE_TYPE_KE_SIGNED, SOURCE_TYPE_KE_RECEIVED, SOURCE_TYPE_HISTORY_PAYROLL);

    private final PerformanceEngine performanceEngine;
    private final ImportToReceivedPort importToReceivedPort;
    private final ReceivedApplyPort receivedApplyPort;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return ImportBatchArchivedEvent.EVENT_TYPE;
    }

    @Override
    public void handle(String eventId, String payloadJson) {
        ImportBatchArchivedEvent event;
        try {
            event = objectMapper.readValue(payloadJson, ImportBatchArchivedEvent.class);
        } catch (JacksonException e) {
            log.error("[导入归档] 事件反序列化失败：eventId={}", eventId, e);
            return;
        }

        // sourceType 过滤
        if (event.getSourceType() == null || !HANDLED_SOURCE_TYPES.contains(event.getSourceType())) {
            log.info("[导入归档] 忽略：batchId={}, sourceType={} (非业绩/实收类源)",
                event.getBatchId(), event.getSourceType());
            return;
        }

        String period = event.getPeriod();
        try {
            log.info("[导入归档] 收到：batchId={}, sourceType={}, period={}",
                event.getBatchId(), event.getSourceType(), period);

            if (SOURCE_TYPE_KE_SIGNED.equals(event.getSourceType())) {
                handleSigned(event, eventId);
            } else {
                handleReceived(event, period);
            }
        } catch (Exception e) {
            log.error("[导入归档] 处理失败：batchId={}, eventId={}", event.getBatchId(), eventId, e);
            throw e;
        }
    }

    /** KE_SIGNED → PerformanceEngine 只建 PERF_EXPECT */
    private void handleSigned(ImportBatchArchivedEvent event, String eventId) {
        List<Long> supersededIds = convertSupersededIds(event.getSupersededBatchIds());
        PerformanceConsumeLog consumeLog = performanceEngine.buildFromBatch(
            event.getBatchId(), eventId, "IMPORT_BATCH_ARCHIVED",
            event.getOperatorId(), supersededIds, event.getSourceType(), event.getPeriod());
        log.info("[导入归档] KE_SIGNED 消费完成：batchId={}, period={}",
            event.getBatchId(), consumeLog.getPeriod());
    }

    /** KE_RECEIVED / HISTORY_PAYROLL → ImportToReceivedPort 写实收表 + 建审批单 */
    private void handleReceived(ImportBatchArchivedEvent event, String period) {
        // 重复导入冲销：先作废旧批次 ACTIVE 实收明细，释放 source_key 唯一锚点，避免新批次全被幂等跳过
        importToReceivedPort.supersedeBatches(convertSupersededIds(event.getSupersededBatchIds()));
        ImportToReceivedPort.ImportToReceivedResult result = importToReceivedPort
            .consumeBatch(event.getBatchId(), period, event.getSourceType(), event.getOperatorId());
        log.info("[导入归档] 实收表写入完成：batchId={}, sourceType={}, contracts={}, details={}",
            event.getBatchId(), event.getSourceType(), result.newContracts(), result.newDetails());

        // 实收审批单
        int created;
        if (SOURCE_TYPE_HISTORY_PAYROLL.equals(event.getSourceType())) {
            created = receivedApplyPort.autoCreateApprovedForBatch(event.getBatchId(), period, event.getOperatorId());
        } else {
            created = receivedApplyPort.autoCreateForReceivedBatch(event.getBatchId(), period, event.getOperatorId());
        }
        log.info("[导入归档] 实收审批单新建：batchId={}, created={}", event.getBatchId(), created);
    }

    private List<Long> convertSupersededIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        java.util.List<Long> result = new java.util.ArrayList<>(ids.size());
        for (String id : ids) {
            try { result.add(Long.valueOf(id)); } catch (NumberFormatException ignore) {}
        }
        return result;
    }
}
