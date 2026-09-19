package com.panjia.people.handler;

import com.panjia.contracts.dto.ScoreSummarySyncDTO;
import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.ImportBatchArchivedEvent;
import com.panjia.people.service.ScoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 导入批次归档事件处理器（员工域积分同步）。
 * <p>
 * 与考勤域 AttendanceArchiveHandler 同构：由 panjia-outbox 的
 * OutboxDispatcher 按 eventType 路由调用（不依赖进程内同步事件）。
 * sourceType=POINTS 时消费 payload 中的积分月度汇总（import 域
 * ScoreSummaryAggregator 推模式），upsert 员工域积分表——
 * 同人同月覆盖，事件重投幂等；同步成功后该期间积分审批单自动失效回待提交。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScoreArchiveHandler implements DomainEventHandler {

    private final ScoreService scoreService;
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
            log.error("[积分消费] 归档事件反序列化失败：eventId={}", eventId, e);
            return;
        }

        if (!"POINTS".equals(event.getSourceType())) {
            return;
        }
        List<ScoreSummarySyncDTO> summaries = event.getScoreSummaries();
        if (summaries == null || summaries.isEmpty()) {
            log.info("[积分消费] 归档事件无积分汇总：batchId={}, period={}", event.getBatchId(), event.getPeriod());
            return;
        }
        // 抛出异常交由 Dispatcher 重试（upsert 幂等，重投安全）
        scoreService.syncScoreSummaries(event.getPeriod(), summaries);
        log.info("[积分消费] 归档事件积分同步完成：batchId={}, period={}, 人数={}",
            event.getBatchId(), event.getPeriod(), summaries.size());
    }
}
