package com.panjia.commission.handler;

import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.event.ApprovalEvent;
import com.panjia.commission.service.CommissionAdjustService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 结佣调整工作流回调监听器。
 * <p>
 * 监听 bizType = {@link BizType#COMMISSION_ADJUST} 的中立审批事件，根据流程状态回调
 * {@link CommissionAdjustService#handleWorkflowEvent} 更新调整单状态并执行调整。
 * <p>
 * 状态映射：
 * <ul>
 *   <li>finish（审批通过）→ 执行调整，状态 EXECUTED</li>
 *   <li>invalid / termination（作废/终止）→ 状态 REJECTED</li>
 *   <li>cancel（撤销）→ 状态 CANCELLED</li>
 * </ul>
 * <p>
 * 异常处理：回调失败时必须重新抛出异常。handleWorkflowEvent 的 @Transactional(REQUIRED)
 * 与外层 completeTask 共享事务，若内部抛异常会将事务标记为 rollback-only；若此处 catch 后
 * 不重抛，外层事务提交时会抛出 {@code UnexpectedRollbackException: Transaction rolled back
 * because it has been marked as rollback-only}，掩盖真实业务错误。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommissionAdjustWorkflowListener {

    private final CommissionAdjustService adjustService;

    @EventListener(condition = "#approvalEvent.bizType == '" + BizType.COMMISSION_ADJUST + "'")
    public void onApprovalEvent(ApprovalEvent approvalEvent) {
        Long adjustId = null;
        try {
            adjustId = approvalEvent.getBizId();
            if (adjustId == null) {
                log.warn("[结佣调整工作流] bizId 为空，跳过：{}", approvalEvent);
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

            log.info("[结佣调整工作流] 回调：adjustId={}, status={}, nodeCode={}",
                adjustId, status, approvalEvent.getNodeCode());

            adjustService.handleWorkflowEvent(adjustId, status, handler, message);
        } catch (Exception e) {
            log.error("[结佣调整工作流] 回调处理失败，事务将回滚：adjustId={}, event={}",
                adjustId, approvalEvent, e);
            // 业务异常原样抛：真实原因经全局异常处理器直达审批人前端；
            // 非业务异常包装后抛，事务回滚行为两者一致
            if (e instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw new IllegalStateException(
                "结佣调整工作流回调失败：adjustId=" + adjustId + ", status=" + approvalEvent.getStatus(), e);
        }
    }
}
