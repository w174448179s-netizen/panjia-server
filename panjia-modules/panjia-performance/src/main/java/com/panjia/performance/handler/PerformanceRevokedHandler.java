package com.panjia.performance.handler;

import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.ImportBatchRevokedEvent;
import com.panjia.performance.mapper.PerformanceFactMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 导入批次撤销事件处理器（业绩域）。
 * <p>
 * KE_SIGNED 批次撤销 → 软删该批次 PERF_EXPECT 事实（fact_status→REVERSED，reason=BATCH_REVOKED）；
 * PERF_REAL 在拆表后已迁出到实收域，由 ReceivedRevokedHandler 处理。
 * 软删而非物理删：PERF_EXPECT 事实可能已被结佣/调整引用，物理删会断 FK。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PerformanceRevokedHandler implements DomainEventHandler {

    private static final String SOURCE_TYPE_KE_SIGNED = "KE_SIGNED";
    private static final String SOURCE_TYPE_HISTORY_PAYROLL = "HISTORY_PAYROLL";

    private final PerformanceFactMapper factMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return ImportBatchRevokedEvent.EVENT_TYPE;
    }

    @Override
    public void handle(String eventId, String payloadJson) {
        ImportBatchRevokedEvent event;
        try {
            event = objectMapper.readValue(payloadJson, ImportBatchRevokedEvent.class);
        } catch (JacksonException e) {
            log.error("[业绩撤销] 事件反序列化失败：eventId={}", eventId, e);
            return;
        }

        String sourceType = event.getSourceType();
        if (sourceType == null) {
            return;
        }

        switch (sourceType) {
            case SOURCE_TYPE_KE_SIGNED -> handleSigned(event);
            default -> log.info("[业绩撤销] 忽略：batchId={}, sourceType={} (非业绩事实来源)",
                event.getBatchId(), sourceType);
        }
    }

    /** KE_SIGNED → 软删该批次 PERF_EXPECT 事实。 */
    private void handleSigned(ImportBatchRevokedEvent event) {
        int count = factMapper.markBatchRevoked(event.getBatchId(), "PERF_EXPECT", "BATCH_REVOKED");
        log.info("[业绩撤销] KE_SIGNED 批次 PERF_EXPECT 已软删：batchId={}, count={}",
            event.getBatchId(), count);
    }
}
