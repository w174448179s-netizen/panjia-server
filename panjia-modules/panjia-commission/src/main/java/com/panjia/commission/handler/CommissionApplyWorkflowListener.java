package com.panjia.commission.handler;

import com.panjia.commission.service.CommissionApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.workflow.api.event.ProcessEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 结佣审批工作流回调监听器（flowCode = commission_apply，§3）。
 * <p>
 * finish → 单据 LOCKED + 明细 APPROVED + 发结佣通过事件；
 * back → REJECTED；cancel/invalid/termination → CANCELLED（未审批明细冲销）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommissionApplyWorkflowListener {

    private static final String FLOW_CODE = "commission_apply";

    private final CommissionApplicationService applicationService;

    @EventListener(condition = "#processEvent.flowCode == '" + FLOW_CODE + "'")
    public void onProcessEvent(ProcessEvent processEvent) {
        try {
            String businessId = processEvent.getBusinessId();
            if (businessId == null || businessId.isBlank()) {
                log.warn("[结佣工作流] businessId 为空，跳过：{}", processEvent);
                return;
            }
            Long applicationId = Long.valueOf(businessId);
            String handler = null;
            String message = null;
            Map<String, Object> params = processEvent.getParams();
            if (params != null) {
                Object h = params.get("handler");
                Object m = params.get("message");
                handler = h == null ? null : h.toString();
                message = m == null ? null : m.toString();
            }
            log.info("[结佣工作流] 回调：applicationId={}, status={}, nodeCode={}",
                applicationId, processEvent.getStatus(), processEvent.getNodeCode());
            applicationService.handleWorkflowEvent(applicationId, processEvent.getStatus(), handler, message);
        } catch (Exception e) {
            log.error("[结佣工作流] 回调处理失败：{}", processEvent, e);
        }
    }
}
