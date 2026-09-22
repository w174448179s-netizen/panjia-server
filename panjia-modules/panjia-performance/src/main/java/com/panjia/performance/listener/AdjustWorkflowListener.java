package com.panjia.performance.listener;

import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.event.ApprovalEvent;
import com.panjia.performance.service.IPerformanceAdjustService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 业绩调整工作流回调监听器。
 * <p>
 * 监听 bizType = {@link BizType#PERF_ADJUST} 的中立审批事件（由 infrastructure 适配器
 * 从 Warm-Flow ProcessEvent 转译而来），根据流程状态回调
 * {@link IPerformanceAdjustService#handleWorkflowEvent} 更新调整单状态并执行调整。
 * <p>
 * 状态映射：
 * <ul>
 *   <li>finish（审批通过）→ 执行调整，状态 EXECUTED</li>
 *   <li>invalid / termination（作废/终止）→ 状态 REJECTED</li>
 *   <li>cancel（撤销）→ 状态 CANCELLED</li>
 * </ul>
 * <p>
 * 异常处理：回调失败时重新抛出异常，让事件发布方感知监听器失败、
 * 触发上层告警；handleWorkflowEvent 的 @Transactional 会回滚，
 * 避免出现工作流已 finish 但调整单卡在 SUBMITTED 的"半完成"状态。
 * 同时在独立事务中把错误摘要追加到 reason 字段，供运维排查。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdjustWorkflowListener {

    /** reason 字段中追加的失败摘要最大长度（避免截断原 reason） */
    private static final int FAILURE_REASON_MAX = 500;

    private final IPerformanceAdjustService adjustService;

    @EventListener(condition = "#approvalEvent.bizType == '" + BizType.PERF_ADJUST + "'")
    public void onApprovalEvent(ApprovalEvent approvalEvent) {
        Long adjustId = null;
        try {
            adjustId = approvalEvent.getBizId();
            if (adjustId == null) {
                log.warn("[业绩调整工作流] bizId 为空，跳过：{}", approvalEvent);
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

            log.info("[业绩调整工作流] 回调：adjustId={}, status={}, nodeCode={}",
                adjustId, status, approvalEvent.getNodeCode());

            adjustService.handleWorkflowEvent(adjustId, status, handler, message);
        } catch (Exception e) {
            // 不再吞异常：记录详细错误后重新抛出，触发上层告警/重试
            log.error("[业绩调整工作流] 回调处理失败，调整单将保留 SUBMITTED 待人工介入："
                + "adjustId={}, event={}", adjustId, approvalEvent, e);
            // 独立事务把失败摘要追加到 reason 字段，便于运维在列表页直接看到
            if (adjustId != null) {
                try {
                    adjustService.markCallbackFailure(adjustId, e.getMessage());
                } catch (Exception markEx) {
                    log.error("[业绩调整工作流] 追加失败摘要到 reason 也失败：adjustId={}", adjustId, markEx);
                }
            }
            throw new IllegalStateException(
                "业绩调整工作流回调失败：adjustId=" + adjustId + ", status=" + approvalEvent.getStatus(), e);
        }
    }
}
