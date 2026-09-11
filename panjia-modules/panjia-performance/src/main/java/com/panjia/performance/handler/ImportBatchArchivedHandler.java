package com.panjia.performance.handler;

import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.ImportBatchArchivedEvent;
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
 * 由 panjia-outbox 的 OutboxDispatcher 按 {@link #eventType()} 路由调用，
 * 本类不再依赖 Spring ApplicationEvent / @EventListener（避免进程内同步阻塞）。
 * <p>
 * 处理逻辑对应业绩域详细设计 §4.1 ①~⑦：
 * <ul>
 *   <li>① sourceType 过滤（EMPLOYEE / ATTENDANCE / POINTS / OTHERS 忽略）</li>
 *   <li>②~③ 由 PerformanceEngine.buildFromBatch 内部幂等与期间封账校验</li>
 *   <li>④ supersededBatchIds 非空时按 SUPERSEDE reason 冲销旧批次（CR-1）</li>
 *   <li>⑤~⑥ 由 PerformanceEngine.buildFromBatch 分页拉 NormalizedRecord 逐行生成事实</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportBatchArchivedHandler implements DomainEventHandler {

    /** 仅处理这两类来源（业绩域只对 KE_SIGNED / KE_NEW_SIGN 生成业绩，§4.1 ①） */
    private static final java.util.Set<String> PERFORMANCE_SOURCE_TYPES = java.util.Set.of("KE_SIGNED", "KE_NEW_SIGN");

    private final PerformanceEngine performanceEngine;
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
            log.error("[业绩消费] 归档事件反序列化失败：eventId={}", eventId, e);
            return;
        }

        // §4.1 ① sourceType 过滤
        if (event.getSourceType() == null || !PERFORMANCE_SOURCE_TYPES.contains(event.getSourceType())) {
            log.info("[业绩消费] 归档事件忽略：batchId={}, sourceType={} (非业绩类源)",
                event.getBatchId(), event.getSourceType());
            return;
        }

        try {
            log.info("[业绩消费] 收到归档事件：batchId={}, sourceType={}, period={}, supersededBatchIds={}",
                event.getBatchId(), event.getSourceType(), event.getPeriod(), event.getSupersededBatchIds());

            List<Long> supersededIds = convertSupersededIds(event.getSupersededBatchIds());

            performanceEngine.buildFromBatch(
                event.getBatchId(),
                eventId,
                "IMPORT_BATCH_ARCHIVED",
                null,
                supersededIds,
                event.getSourceType(),
                event.getPeriod());

            log.info("[业绩消费] 归档批次消费完成：batchId={}", event.getBatchId());
        } catch (Exception e) {
            log.error("[业绩消费] 归档事件处理失败：batchId={}, eventId={}",
                event.getBatchId(), eventId, e);
            // 原事务已回滚（RUNNING 日志行不复存在），在事务外补记一条 FAILED 审计日志，
            // message 为根因摘要；随后 rethrow 交 OutboxDispatcher 退避重试
            performanceEngine.markConsumeFailed(event.getBatchId(), eventId, "IMPORT_BATCH_ARCHIVED",
                event.getSourceType(), event.getPeriod(), null, e);
            throw e;
        }
    }

    /**
     * supersededBatchIds 字段在事件 payload 中是 List&lt;String&gt;（JSON 序列化兼容性），
     * PerformanceEngine.buildFromBatch 接收 List&lt;Long&gt;，此处做转换。
     */
    private List<Long> convertSupersededIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        java.util.List<Long> result = new java.util.ArrayList<>(ids.size());
        for (String id : ids) {
            try {
                result.add(Long.valueOf(id));
            } catch (NumberFormatException e) {
                log.warn("[业绩消费] supersededBatchIds 含非法 ID：{}", id);
            }
        }
        return result;
    }
}