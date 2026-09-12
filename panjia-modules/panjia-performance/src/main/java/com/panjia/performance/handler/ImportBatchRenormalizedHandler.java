package com.panjia.performance.handler;

import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.ImportBatchRenormalizedEvent;
import com.panjia.performance.service.PerformanceEngine;
import com.panjia.performance.service.ReverseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;

/**
 * 导入批次重归一化事件处理器（DomainEventHandler，CR-5）。
 * <p>
 * 处理流程（业绩域详细设计 §4.3）：
 * <ol>
 *   <li>按 sourceType 过滤（仅唯一业绩来源 KE_SIGNED 进业绩）</li>
 *   <li>先调用 ReverseService.reverseByReNormalize 冲销本批次的全部 ACTIVE 事实
 *       （reversed_reason = RENORMALIZE）</li>
 *   <li>调用 PerformanceEngine.buildFromBatch 重新生成本批次事实</li>
 * </ol>
 * <p>
 * 与归档事件 ImportBatchArchivedHandler 的区别：归档事件会同时冲销其他被 supersede
 * 的旧批次（CR-1），而重归一化仅处理本批次自身。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportBatchRenormalizedHandler implements DomainEventHandler {

    /** 与 ImportBatchArchivedHandler 保持一致：仅唯一业绩来源 KE_SIGNED 进业绩 */
    private static final java.util.Set<String> PERFORMANCE_SOURCE_TYPES = java.util.Set.of("KE_SIGNED");

    private final PerformanceEngine performanceEngine;
    private final ReverseService reverseService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return ImportBatchRenormalizedEvent.EVENT_TYPE;
    }

    @Override
    public void handle(String eventId, String payloadJson) {
        ImportBatchRenormalizedEvent event;
        try {
            event = objectMapper.readValue(payloadJson, ImportBatchRenormalizedEvent.class);
        } catch (JacksonException e) {
            log.error("[业绩重归一化] 事件反序列化失败：eventId={}", eventId, e);
            return;
        }

        // §4.1 ① sourceType 过滤
        if (event.getSourceType() == null || !PERFORMANCE_SOURCE_TYPES.contains(event.getSourceType())) {
            log.info("[业绩重归一化] 事件忽略：batchId={}, sourceType={} (非业绩类源)",
                event.getBatchId(), event.getSourceType());
            return;
        }

        try {
            log.info("[业绩重归一化] 收到事件：batchId={}, sourceType={}, period={}",
                event.getBatchId(), event.getSourceType(), event.getPeriod());

            // 1. 先冲销本批次全部 ACTIVE 事实（reason = RENORMALIZE）
            int reversedCount = reverseService.reverseByReNormalize(event.getBatchId(), null);
            log.info("[业绩重归一化] 旧事实冲销完成：batchId={}, 冲销数={}",
                event.getBatchId(), reversedCount);

            // 2. 重新生成本批次事实
            performanceEngine.buildFromBatch(
                event.getBatchId(),
                eventId,
                "IMPORT_BATCH_RENORMALIZED",
                null,
                Collections.emptyList(),
                event.getSourceType(),
                event.getPeriod());
            log.info("[业绩重归一化] 重新生成完成：batchId={}", event.getBatchId());
        } catch (Exception e) {
            log.error("[业绩重归一化] 事件处理失败：batchId={}, eventId={}",
                event.getBatchId(), eventId, e);
            // 原事务已回滚（RUNNING 日志行不复存在），在事务外补记一条 FAILED 审计日志，
            // message 为根因摘要；随后 rethrow 交 OutboxDispatcher 退避重试
            performanceEngine.markConsumeFailed(event.getBatchId(), eventId, "IMPORT_BATCH_RENORMALIZED",
                event.getSourceType(), event.getPeriod(), null, e);
            throw e;
        }
    }
}