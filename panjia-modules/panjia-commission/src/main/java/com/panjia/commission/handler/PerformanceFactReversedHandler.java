package com.panjia.commission.handler;

import com.panjia.commission.service.CommissionReverseService;
import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.PerformanceFactReversedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 业绩事实冲销事件处理器（DomainEventHandler，消费 panjia-performance 事件）。
 * <p>
 * 处理逻辑（结佣域详细设计 §4.4 分治表）：委托 {@link CommissionReverseService#handleReversed}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PerformanceFactReversedHandler implements DomainEventHandler {

    private final CommissionReverseService reverseService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return PerformanceFactReversedEvent.EVENT_TYPE;
    }

    @Override
    public void handle(String eventId, String payloadJson) {
        PerformanceFactReversedEvent event;
        try {
            event = objectMapper.readValue(payloadJson, PerformanceFactReversedEvent.class);
        } catch (JacksonException e) {
            log.error("[结佣-冲销联动] 事件反序列化失败：eventId={}", eventId, e);
            return;
        }

        reverseService.handleReversed(eventId, event.getPeriod(),
            event.getFactIds() == null ? List.of() : event.getFactIds(),
            event.getReason());
    }
}
