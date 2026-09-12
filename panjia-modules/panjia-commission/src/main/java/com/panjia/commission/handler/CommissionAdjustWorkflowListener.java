package com.panjia.commission.handler;

import com.panjia.commission.service.CommissionAdjustService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.workflow.api.event.ProcessEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 结佣调整工作流回调监听器。
 * <p>
 * 监听 flowCode = 'commission_adjust' 的流程事件，根据流程状态回调
 * {@link CommissionAdjustService#handleWorkflowEvent} 更新调整单状态并执行调整。
 * <p>
 * 状态映射：
 * <ul>
 *   <li>finish（审批通过）→ 执行调整，状态 EXECUTED</li>
 *   <li>invalid / termination（作废/终止）→ 状态 REJECTED</li>
 *   <li>cancel（撤销）→ 状态 CANCELLED</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommissionAdjustWorkflowListener {

    private static final String FLOW_CODE_COMMISSION_ADJUST = "commission_adjust";

    private final CommissionAdjustService adjustService;

    @EventListener(condition = "#processEvent.flowCode == '" + FLOW_CODE_COMMISSION_ADJUST + "'")
    public void onProcessEvent(ProcessEvent processEvent) {
        try {
            String businessId = processEvent.getBusinessId();
            if (businessId == null || businessId.isBlank()) {
                log.warn("[结佣调整工作流] businessId 为空，跳过：{}", processEvent);
                return;
            }
            Long adjustId = Long.valueOf(businessId);
            String status = processEvent.getStatus();
            String handler = null;
            String message = null;

            Map<String, Object> params = processEvent.getParams();
            if (params != null) {
                Object h = params.get("handler");
                Object m = params.get("message");
                handler = h == null ? null : h.toString();
                message = m == null ? null : m.toString();
            }

            log.info("[结佣调整工作流] 回调：adjustId={}, status={}, nodeCode={}",
                adjustId, status, processEvent.getNodeCode());

            adjustService.handleWorkflowEvent(adjustId, status, handler, message);
        } catch (Exception e) {
            log.error("[结佣调整工作流] 回调处理失败：{}", processEvent, e);
        }
    }
}
