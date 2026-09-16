package com.panjia.performance.handler;

import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.event.ApprovalEvent;
import com.panjia.performance.service.ReceivedApplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 实收业绩审批工作流回调监听器（bizType = {@link BizType#REAL_CONFIRM}）。
 * <p>
 * finish → 单据 APPROVED；back → REJECTED；cancel/invalid/termination → CANCELLED。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReceivedWorkflowListener {

    private final ReceivedApplyService receivedApplyService;

    @EventListener(condition = "#approvalEvent.bizType == '" + BizType.REAL_CONFIRM + "'")
    public void onApprovalEvent(ApprovalEvent approvalEvent) {
        try {
            Long applyId = approvalEvent.getBizId();
            if (applyId == null) {
                log.warn("[实收审批工作流] bizId 为空，跳过：{}", approvalEvent);
                return;
            }
            String handler = null;
            String message = null;
            Map<String, Object> params = approvalEvent.getParams();
            if (params != null) {
                Object h = params.get("handler");
                Object m = params.get("message");
                handler = h == null ? null : h.toString();
                message = m == null ? null : m.toString();
            }
            log.info("[实收审批工作流] 回调：applyId={}, status={}, nodeCode={}",
                applyId, approvalEvent.getStatus(), approvalEvent.getNodeCode());
            receivedApplyService.handleWorkflowEvent(applyId, approvalEvent.getStatus(), handler, message);
        } catch (Exception e) {
            log.error("[实收审批工作流] 回调处理失败：{}", approvalEvent, e);
        }
    }
}
