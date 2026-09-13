package com.panjia.commission.handler;

import com.panjia.commission.service.CommissionReverseService;
import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.PerformanceFactCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 业绩事实创建事件处理器（DomainEventHandler，消费 panjia-performance 事件）。
 * <p>
 * 处理逻辑：仅做消费留痕，<b>不自动建单</b>。
 * <ul>
 *   <li>申请单粒度 = 合同 + 月，进工资必须经人工在结佣申请页按合同（或批量）发起 + 审批；</li>
 *   <li>数据可分多次导入，未发起的合同在列表中以「未发起」展示，随到随发起；</li>
 *   <li>PERF_EXPECT 新签口径不走结佣明细，仅记录忽略日志。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PerformanceFactCreatedHandler implements DomainEventHandler {

    /** 结佣口径：仅实收业绩触发 */
    private static final String FACT_TYPE_REAL = "PERF_REAL";

    private final CommissionReverseService reverseService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return PerformanceFactCreatedEvent.EVENT_TYPE;
    }

    @Override
    public void handle(String eventId, String payloadJson) {
        PerformanceFactCreatedEvent event;
        try {
            event = objectMapper.readValue(payloadJson, PerformanceFactCreatedEvent.class);
        } catch (JacksonException e) {
            log.error("[结佣-事实创建] 事件反序列化失败：eventId={}", eventId, e);
            return;
        }

        List<String> factIds = event.getFactIds() == null ? List.of() : event.getFactIds();
        if (!FACT_TYPE_REAL.equals(event.getFactType())) {
            log.info("[结佣-事实创建] 非实收口径事实，忽略：eventId={}, factType={}", eventId, event.getFactType());
            return;
        }

        // 仅留痕：合同申请单由人工在结佣申请页发起（按合同或批量）
        reverseService.recordFactCreated(eventId, event.getPeriod(), factIds,
            "实收事实已就绪，等待人工按合同发起结佣");
        log.info("[结佣-事实创建] 实收事实已记录，等待人工发起：eventId={}, period={}, factCount={}",
            eventId, event.getPeriod(), factIds.size());
    }
}
