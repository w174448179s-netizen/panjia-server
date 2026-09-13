package com.panjia.payroll.handler;

import com.panjia.contracts.event.CommissionApprovedEvent;
import com.panjia.contracts.event.DomainEventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 结佣审批通过事件处理器（DomainEventHandler，panjia-contracts Port 实现）。
 * <p>
 * 由 panjia-outbox 的 OutboxDispatcher 按 {@link #eventType()} 路由调用，
 * 本类不依赖 Spring ApplicationEvent / @EventListener（避免进程内同步阻塞）。
 * <p>
 * 消费逻辑（薪资域详细设计）：
 * <ul>
 *   <li>仅记录日志，标记结佣审批通过事件已被 payroll 域接收；</li>
 *   <li>不自动触发算薪（算薪需人工发起，结佣数据在
 *       {@code PayrollBatchService.calculate()} 中通过查询已审批结佣事实自动拉取）；</li>
 *   <li>事件只做通知，明细数据由 payroll 经 {@code CommissionQueryPort} 拉取（CI C14）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommissionApprovedHandler implements DomainEventHandler {

    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return CommissionApprovedEvent.EVENT_TYPE;
    }

    @Override
    public void handle(String eventId, String payloadJson) {
        CommissionApprovedEvent event;
        try {
            event = objectMapper.readValue(payloadJson, CommissionApprovedEvent.class);
        } catch (JacksonException e) {
            log.error("[薪资-结佣审批] 事件反序列化失败：eventId={}", eventId, e);
            return;
        }

        log.info("[薪资-结佣审批] 结佣审批通过事件已接收，payroll 将在算薪时自动拉取已审批结佣数据："
                + "eventId={}, applicationId={}, period={}, approvedMonth={}, deptId={}, itemCount={}",
            eventId, event.getApplicationId(), event.getPeriod(),
            event.getApprovedMonth(), event.getDeptId(),
            event.getItemIds() == null ? 0 : event.getItemIds().size());
    }
}
