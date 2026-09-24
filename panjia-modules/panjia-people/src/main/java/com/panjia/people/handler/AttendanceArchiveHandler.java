package com.panjia.people.handler;

import com.panjia.contracts.dto.AttendanceSummarySyncDTO;
import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.ImportBatchArchivedEvent;
import com.panjia.people.service.AttendanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 导入批次归档事件处理器（员工域考勤同步）。
 * <p>
 * 与业绩域 ImportBatchArchivedHandler 同构：由 panjia-outbox 的
 * OutboxDispatcher 按 eventType 路由调用（不依赖进程内同步事件）。
 * sourceType=ATTENDANCE 时消费 payload 中的考勤月度汇总（import 域
 * AttendanceSummaryAggregator 推模式），upsert 员工域考勤汇总表——
 * 同人同月覆盖，事件重投幂等；同步成功后该期间考勤审批单自动失效回待提交。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttendanceArchiveHandler implements DomainEventHandler {

    private final AttendanceService attendanceService;
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
            log.error("[考勤消费] 归档事件反序列化失败：eventId={}", eventId, e);
            return;
        }

        // 历史工资导入：考勤汇总 upsert（data_source=IMPORT）+ 审批单 APPROVED 直建
        if ("HISTORY_PAYROLL".equals(event.getSourceType())) {
            List<AttendanceSummarySyncDTO> history = event.getAttendanceSummaries();
            if (history == null || history.isEmpty()) {
                return;
            }
            attendanceService.syncHistorySummaries(event.getPeriod(), history);
            log.info("[考勤消费] 历史导入考勤同步完成：batchId={}, period={}, 人数={}",
                event.getBatchId(), event.getPeriod(), history.size());
            return;
        }

        if (!"ATTENDANCE".equals(event.getSourceType())) {
            return;
        }
        List<AttendanceSummarySyncDTO> summaries = event.getAttendanceSummaries();
        if (summaries == null || summaries.isEmpty()) {
            log.info("[考勤消费] 归档事件无考勤汇总：batchId={}, period={}", event.getBatchId(), event.getPeriod());
            return;
        }
        // 抛出异常交由 Dispatcher 重试（upsert 幂等，重投安全）
        attendanceService.syncAttendanceSummaries(event.getPeriod(), summaries);
        log.info("[考勤消费] 归档事件考勤同步完成：batchId={}, period={}, 人数={}",
            event.getBatchId(), event.getPeriod(), summaries.size());
    }
}
