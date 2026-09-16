package com.panjia.payroll.handler;

import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.event.ApprovalEvent;
import com.panjia.contracts.event.ApprovalTaskEvent;
import com.panjia.payroll.service.PayrollBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 算薪批次工作流回调监听器（bizType = {@link BizType#PAYROLL_BATCH}）。
 * <p>
 * 实例级 {@link ApprovalEvent}：finish（总监锁定节点办理完成）→ 批次 LOCKED +
 * 发布 PayrollLockedEvent；back（总监审核驳回）→ CALCULATED；
 * cancel/invalid/termination → CALCULATED 并解除实例绑定。
 * <p>
 * 任务级 {@link ApprovalTaskEvent}：payroll_review 任务创建 → REVIEWING；
 * payroll_lock 任务创建（总监审核已通过）→ APPROVED。
 * <p>
 * 审批动作全部经「我的待办」由引擎按 flow_user 名单判权办理，
 * 不存在业务接口直改状态的业务直批路径。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayrollBatchWorkflowListener {

    private static final String NODE_REVIEW = "payroll_review";
    private static final String NODE_LOCK = "payroll_lock";

    private final PayrollBatchService batchService;

    @EventListener(condition = "#approvalEvent.bizType == '" + BizType.PAYROLL_BATCH + "'")
    public void onApprovalEvent(ApprovalEvent approvalEvent) {
        try {
            Long batchId = approvalEvent.getBizId();
            if (batchId == null) {
                log.warn("[薪酬工作流] bizId 为空，跳过：{}", approvalEvent);
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
            log.info("[薪酬工作流] 回调：batchId={}, status={}, nodeCode={}",
                batchId, approvalEvent.getStatus(), approvalEvent.getNodeCode());
            batchService.handleWorkflowEvent(batchId, approvalEvent.getStatus(), handler, message);
        } catch (Exception e) {
            log.error("[薪酬工作流] 回调处理失败：{}", approvalEvent, e);
        }
    }

    @EventListener(condition = "#approvalTaskEvent.bizType == '" + BizType.PAYROLL_BATCH
        + "' && (#approvalTaskEvent.nodeCode == '" + NODE_REVIEW
        + "' || #approvalTaskEvent.nodeCode == '" + NODE_LOCK + "')")
    public void onTaskNodeCreated(ApprovalTaskEvent approvalTaskEvent) {
        try {
            Long batchId = approvalTaskEvent.getBizId();
            if (batchId == null) {
                log.warn("[薪酬工作流] 任务事件 bizId 为空，跳过：{}", approvalTaskEvent);
                return;
            }
            log.info("[薪酬工作流] 节点任务创建：batchId={}, node={}", batchId, approvalTaskEvent.getNodeCode());
            batchService.handleTaskNodeEvent(batchId, approvalTaskEvent.getNodeCode());
        } catch (Exception e) {
            log.error("[薪酬工作流] 节点事件处理失败：{}", approvalTaskEvent, e);
        }
    }
}
