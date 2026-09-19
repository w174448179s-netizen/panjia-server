package com.panjia.people.handler;

import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.ImportBatchRevokedEvent;
import com.panjia.people.service.AttendanceApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 导入批次撤销事件处理器（员工域考勤）。
 * <p>
 * 考勤批次被撤销后，该期间考勤数据失去数据来源（DINGTALK 同步数据无法
 * 精确回滚 upsert 覆盖），故将对应期间的审批单失效回待提交——已提交/已通过
 * 的审批不可继续用于算薪，须人事复核数据后重新提交审批。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttendanceRevokedHandler implements DomainEventHandler {

    private final AttendanceApprovalService approvalService;
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
            log.error("[考勤消费] 撤销事件反序列化失败：eventId={}", eventId, e);
            return;
        }

        if (!"ATTENDANCE".equals(event.getSourceType())) {
            return;
        }
        approvalService.invalidateOnDataChange(event.getPeriod());
        log.info("[考勤消费] 批次撤销，考勤审批单失效：batchId={}, period={}", event.getBatchId(), event.getPeriod());
    }
}
