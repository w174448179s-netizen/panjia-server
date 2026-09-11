package com.panjia.performance.listener;

import com.panjia.performance.event.ImportBatchArchivedEvent;
import com.panjia.performance.event.ImportBatchRenormalizedEvent;
import com.panjia.performance.service.PerformanceEngine;
import com.panjia.performance.service.ReverseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 导入域事件监听器。
 * <p>
 * 监听导入域发布的批次相关事件，触发业绩消费或冲销重建。
 * 事件监听异常不向上抛出，避免影响主流程。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportEventListener {

    private final PerformanceEngine performanceEngine;
    private final ReverseService reverseService;

    /**
     * 监听导入批次归档事件，触发业绩消费。
     *
     * @param event 导入批次归档事件
     */
    @EventListener
    public void onImportBatchArchived(ImportBatchArchivedEvent event) {
        try {
            log.info("[业绩消费] 收到导入批次归档事件：batchId={}, eventId={}",
                    event.getBatchId(), event.getEventId());
            performanceEngine.buildFromBatch(
                    event.getBatchId(),
                    event.getEventId(),
                    "IMPORT_BATCH_ARCHIVED",
                    null);
            log.info("[业绩消费] 批次消费完成：batchId={}", event.getBatchId());
        } catch (Exception e) {
            log.error("[业绩消费] 导入批次归档事件处理失败：batchId={}, eventId={}",
                    event.getBatchId(), event.getEventId(), e);
        }
    }

    /**
     * 监听导入批次重归一化事件，先冲销旧事实再重新生成业绩。
     *
     * @param event 导入批次重归一化事件
     */
    @EventListener
    public void onImportBatchRenormalized(ImportBatchRenormalizedEvent event) {
        try {
            log.info("[业绩冲销重建] 收到导入批次重归一化事件：batchId={}, eventId={}",
                    event.getBatchId(), event.getEventId());

            // 1. 先冲销旧事实
            int reversedCount = reverseService.reverseByReNormalize(event.getBatchId(), null);
            log.info("[业绩冲销重建] 旧事实冲销完成：batchId={}, 冲销数={}",
                    event.getBatchId(), reversedCount);

            // 2. 重新生成业绩
            performanceEngine.buildFromBatch(
                    event.getBatchId(),
                    event.getEventId(),
                    "IMPORT_BATCH_RENORMALIZED",
                    null);
            log.info("[业绩冲销重建] 重新生成完成：batchId={}", event.getBatchId());
        } catch (Exception e) {
            log.error("[业绩冲销重建] 导入批次重归一化事件处理失败：batchId={}, eventId={}",
                    event.getBatchId(), event.getEventId(), e);
        }
    }
}
