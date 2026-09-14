package com.panjia.performance.handler;

import com.panjia.performance.service.ReceivedApplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.workflow.api.event.ProcessEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 实收业绩审批工作流回调监听器（flowCode = perf_received）。
 * <p>
 * finish → 单据 APPROVED；back → REJECTED；cancel/invalid/termination → CANCELLED。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReceivedWorkflowListener {

    private static final String FLOW_CODE = "perf_received";

    private final ReceivedApplyService receivedApplyService;

    @EventListener(condition = "#processEvent.flowCode == '" + FLOW_CODE + "'")
    public void onProcessEvent(ProcessEvent processEvent) {
        try {
            String businessId = processEvent.getBusinessId();
            if (businessId == null || businessId.isBlank()) {
                log.warn("[实收审批工作流] businessId 为空，跳过：{}", processEvent);
                return;
            }
            Long applyId = Long.valueOf(businessId);
            String handler = null;
            String message = null;
            Map<String, Object> params = processEvent.getParams();
            if (params != null) {
                Object h = params.get("handler");
                Object m = params.get("message");
                handler = h == null ? null : h.toString();
                message = m == null ? null : m.toString();
            }
            log.info("[实收审批工作流] 回调：applyId={}, status={}, nodeCode={}",
                applyId, processEvent.getStatus(), processEvent.getNodeCode());
            receivedApplyService.handleWorkflowEvent(applyId, processEvent.getStatus(), handler, message);
        } catch (Exception e) {
            log.error("[实收审批工作流] 回调处理失败：{}", processEvent, e);
        }
    }
}
