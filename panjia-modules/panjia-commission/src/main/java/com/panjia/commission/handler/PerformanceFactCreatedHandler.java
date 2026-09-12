package com.panjia.commission.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.commission.domain.ApplicationStatus;
import com.panjia.commission.domain.CommissionApplication;
import com.panjia.commission.mapper.CommissionApplicationMapper;
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
 * 处理逻辑（结佣域详细设计 §7.1）：
 * <ul>
 *   <li>仅 {@code PERF_REAL} 触发；PERF_EXPECT 忽略（新签透传不走结佣明细）；</li>
 *   <li>若该 (period, deptId) 存在 DRAFT/SUBMITTED 申请单 → 标记「有待补事实」并提示算薪人员
 *       执行增量重拉（§4.1.1）；<b>不自动创建明细</b>（进工资必须经人工发起 + 审批）；</li>
 *   <li>无未审批申请单 → 事实暂不入结佣，仅留痕。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PerformanceFactCreatedHandler implements DomainEventHandler {

    /** 结佣口径：仅实收业绩触发 */
    private static final String FACT_TYPE_REAL = "PERF_REAL";

    private final CommissionApplicationMapper applicationMapper;
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

        // PERF_EXPECT 忽略（新签透传不生成明细）
        if (!FACT_TYPE_REAL.equals(event.getFactType())) {
            log.info("[结佣-事实创建] 非实收口径事实，忽略：eventId={}, factType={}", eventId, event.getFactType());
            return;
        }

        // 检查各门店是否有 DRAFT/SUBMITTED 申请单 → 提示增量重拉
        List<String> deptIds = event.getDeptIds() == null ? List.of() : event.getDeptIds();
        StringBuilder tip = new StringBuilder();
        for (String deptIdStr : deptIds) {
            Long deptId;
            try {
                deptId = Long.valueOf(deptIdStr);
            } catch (NumberFormatException e) {
                continue;
            }
            CommissionApplication pendingApp = applicationMapper.selectOne(
                new LambdaQueryWrapper<CommissionApplication>()
                    .eq(CommissionApplication::getPeriod, event.getPeriod())
                    .eq(CommissionApplication::getDeptId, deptId)
                    .in(CommissionApplication::getStatus,
                        ApplicationStatus.DRAFT, ApplicationStatus.SUBMITTED)
                    .orderByDesc(CommissionApplication::getCreateTime)
                    .last("LIMIT 1"));
            if (pendingApp != null) {
                String msg = "期间 " + event.getPeriod() + " 门店 " + deptId
                    + " 存在未审批申请单（" + pendingApp.getApplyNo() + "），有待补事实，请执行增量重拉";
                tip.append(msg).append("；");
                log.info("[结佣-事实创建] {}", msg);
            }
        }
        if (tip.isEmpty()) {
            log.info("[结佣-事实创建] 无未审批申请单，事实暂不入结佣（待人工发起）：eventId={}, period={}",
                eventId, event.getPeriod());
        }

        reverseService.recordFactCreated(eventId, event.getPeriod(),
            event.getFactIds() == null ? List.of() : event.getFactIds(),
            tip.isEmpty() ? "无未审批申请单，事实暂不入结佣" : tip.toString());
    }
}
