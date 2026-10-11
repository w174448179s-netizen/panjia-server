package com.panjia.performance.listener;

import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.event.ApprovalEvent;
import com.panjia.performance.service.IReceivedAdjustService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 实收调整工作流回调监听器（flowCode = perf_received_adjust）。
 * <p>
 * 监听 bizType = {@link BizType#PERF_RECEIVED_ADJUST} 的中立审批事件，
 * 回调 {@link IReceivedAdjustService#handleWorkflowEvent} 更新实收调整单状态并执行调整。
 * <p>
 * 兼容：2026-10-12 拆表前创建的在途实收单仍挂在 perf_adjust 流程定义下，
 * 其事件 bizType=PERF_ADJUST，由 {@link AdjustWorkflowListener} 查无新签单后委托实收服务处理
 * （工作流实例按 businessId 定位，与 bizType 无关）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReceivedAdjustWorkflowListener {

    private final IReceivedAdjustService receivedAdjustService;

    @EventListener(condition = "#approvalEvent.bizType == '" + BizType.PERF_RECEIVED_ADJUST + "'")
    public void onApprovalEvent(ApprovalEvent approvalEvent) {
        Long adjustId = null;
        try {
            adjustId = approvalEvent.getBizId();
            if (adjustId == null) {
                log.warn("[实收调整工作流] bizId 为空，跳过：{}", approvalEvent);
                return;
            }
            String status = approvalEvent.getStatus();
            String handler = null;
            String message = null;
            Map<String, Object> params = approvalEvent.getParams();
            if (params != null) {
                Object h = params.get("handler");
                Object m = params.get("message");
                handler = h == null ? null : h.toString();
                message = m == null ? null : m.toString();
            }
            log.info("[实收调整工作流] 回调：adjustId={}, status={}, nodeCode={}",
                adjustId, status, approvalEvent.getNodeCode());
            receivedAdjustService.handleWorkflowEvent(adjustId, status, handler, message);
        } catch (Exception e) {
            log.error("[实收调整工作流] 回调处理失败，调整单将保留 SUBMITTED 待人工介入："
                + "adjustId={}, event={}", adjustId, approvalEvent, e);
            // 业务异常原样抛，真实原因直达审批人前端
            if (e instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw new IllegalStateException(
                "实收调整工作流回调失败：adjustId=" + adjustId + ", status=" + approvalEvent.getStatus(), e);
        }
    }
}
