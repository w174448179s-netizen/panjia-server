package com.panjia.people.handler;

import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.event.ApprovalEvent;
import com.panjia.people.service.AttendanceRectifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 考勤修改申请工作流回调监听器（bizType = {@link BizType#ATTENDANCE_RECTIFY}）。
 * <p>
 * 实例级 {@link ApprovalEvent}（WarmFlowEventTranslator 转译）：
 * finish（人事审核通过）→ 生效修改 + APPROVED；
 * back（人事驳回）→ REJECTED（原因取 message）并清理回退的流程实例；
 * cancel/invalid/termination → CANCELED（幂等）。
 * <p>
 * 异常处理：回调失败时必须重抛异常，否则与外层 completeTask 共享的事务已被标记
 * rollback-only，提交时会抛出 UnexpectedRollbackException 掩盖真实业务错误。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttendanceRectifyWorkflowListener {

    private final AttendanceRectifyService rectifyService;

    @EventListener(condition = "#approvalEvent.bizType == '" + BizType.ATTENDANCE_RECTIFY + "'")
    public void onApprovalEvent(ApprovalEvent approvalEvent) {
        Long bizId = null;
        try {
            bizId = approvalEvent.getBizId();
            if (bizId == null) {
                log.warn("[考勤修改工作流] bizId 为空，跳过：{}", approvalEvent);
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
            log.info("[考勤修改工作流] 回调：bizId={}, status={}, nodeCode={}",
                bizId, approvalEvent.getStatus(), approvalEvent.getNodeCode());
            rectifyService.handleWorkflowEvent(bizId, approvalEvent.getStatus(), handler, message);
        } catch (Exception e) {
            log.error("[考勤修改工作流] 回调处理失败，事务将回滚：bizId={}, event={}", bizId, approvalEvent, e);
            if (e instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw new IllegalStateException("考勤修改审批工作流回调失败：bizId=" + bizId
                + ", status=" + approvalEvent.getStatus(), e);
        }
    }
}
