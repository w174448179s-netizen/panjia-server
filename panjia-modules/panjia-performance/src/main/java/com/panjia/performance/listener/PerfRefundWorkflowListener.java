package com.panjia.performance.listener;

import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.event.ApprovalEvent;
import com.panjia.performance.service.IPerfRefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 退单工作流回调监听器（flowCode = perf_refund）。
 * <p>
 * 监听 bizType = {@link BizType#PERF_REFUND} 的中立审批事件：
 * finish → 置 APPROVED 并立即生成负新签 + 负实收流水后置 EXECUTED；
 * back / invalid / termination / cancel → 置 REJECTED。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PerfRefundWorkflowListener {

    private final IPerfRefundService refundService;

    @EventListener(condition = "#approvalEvent.bizType == '" + BizType.PERF_REFUND + "'")
    public void onApprovalEvent(ApprovalEvent approvalEvent) {
        Long refundId = null;
        try {
            refundId = approvalEvent.getBizId();
            if (refundId == null) {
                log.warn("[退单工作流] bizId 为空，跳过：{}", approvalEvent);
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
            log.info("[退单工作流] 回调：refundId={}, status={}, nodeCode={}",
                refundId, approvalEvent.getStatus(), approvalEvent.getNodeCode());
            refundService.handleWorkflowEvent(refundId, approvalEvent.getStatus(), handler, message);
        } catch (Exception e) {
            log.error("[退单工作流] 回调处理失败，退单将保留原状态待人工介入：refundId={}, event={}",
                refundId, approvalEvent, e);
            if (e instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw new IllegalStateException(
                "退单工作流回调失败：refundId=" + refundId + ", status=" + approvalEvent.getStatus(), e);
        }
    }
}
