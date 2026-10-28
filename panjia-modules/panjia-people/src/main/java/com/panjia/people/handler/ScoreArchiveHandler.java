package com.panjia.people.handler;

import com.panjia.contracts.dto.ScoreDetailSyncDTO;
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

        // 历史工资导入：积分汇总 upsert + 审批单 APPROVED 直建
        if ("HISTORY_PAYROLL".equals(event.getSourceType())) {
            List<ScoreSummarySyncDTO> history = event.getScoreSummaries();
            if (history == null || history.isEmpty()) {
                return;
            }
            scoreService.syncHistorySummaries(event.getPeriod(), history);
            log.info("[积分消费] 历史导入积分同步完成：batchId={}, period={}, 人数={}",
                event.getBatchId(), event.getPeriod(), history.size());
            return;
        }

        if (!"POINTS".equals(event.getSourceType())) {
            return;
        }
        List<ScoreSummarySyncDTO> summaries = event.getScoreSummaries();
        List<ScoreDetailSyncDTO> details = event.getScoreDetails();
        boolean hasDetails = details != null && !details.isEmpty();
        if (!hasDetails && (summaries == null || summaries.isEmpty())) {
            log.info("[积分消费] 归档事件无积分数据：batchId={}, period={}", event.getBatchId(), event.getPeriod());
            return;
        }
        if (hasDetails) {
            // 明细按 (员工, 日期) 覆盖写入，月度汇总由明细全量重算——
            // 同月多个批次（二手 + 租赁）按日期合并，后导入者覆盖同日数据
            scoreService.syncScoreDetails(event.getPeriod(), details, toBatchIds(event.getSupersededBatchIds()));
        } else {
            // 兼容无明细的旧事件：退回批内汇总覆盖
            scoreService.syncScoreSummaries(event.getPeriod(), summaries);
        }
        log.info("[积分消费] 归档事件积分同步完成：batchId={}, period={}, 明细行={}",
            event.getBatchId(), event.getPeriod(), hasDetails ? details.size() : 0);
    }

    /** 事件中的批次 ID 为字符串，转换为 Long（非法值忽略） */
    private List<Long> toBatchIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
            .filter(id -> id != null && !id.isBlank())
            .map(id -> {
                try {
                    return Long.valueOf(id.trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            })
            .filter(id -> id != null)
            .toList();
    }
}
