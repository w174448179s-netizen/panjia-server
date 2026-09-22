package com.panjia.performance.listener;

import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.event.ApprovalEvent;
import com.panjia.contracts.event.ApprovalTaskEvent;
import com.panjia.performance.service.IReceivedApplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 实收业绩审批工作流回调监听器（bizType = {@link BizType#REAL_CONFIRM}）。
 * <p>
 * 实例级 {@link ApprovalEvent}：finish → 单据 APPROVED；back → REJECTED；cancel/invalid/termination → CANCELLED。
 * <p>
 * 任务级 {@link ApprovalTaskEvent}（语义：代表任务创建、亦代表上一节点已完成）：
 * 流转进入 rcv_director（即财务节点已办理）→ 回填最近审批人/审批时间。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReceivedWorkflowListener {

    /** 实收流程总监节点编码（与 ReceivedApplyServiceImpl.NODE_DIRECTOR 一致） */
    private static final String NODE_DIRECTOR = "rcv_director";

    private final IReceivedApplyService receivedApplyService;

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

    /**
     * 总监节点任务创建 = 财务节点已办理完成：回填最近审批人/审批时间。
     * <p>params 为上一任务（财务任务）的办理参数，handler 即财务用户 ID。
     * 覆盖「我的待办 → 去处理 → 通过」的原生 completeTask 路径——该路径不经过
     * 业务 approve 入口，若不在此回填，总监待审期间审批人/审批时间显示为空。</p>
     */
    @EventListener(condition = "#approvalTaskEvent.bizType == '" + BizType.REAL_CONFIRM
        + "' && #approvalTaskEvent.nodeCode == '" + NODE_DIRECTOR + "'")
    public void onDirectorTaskCreated(ApprovalTaskEvent approvalTaskEvent) {
        try {
            Long applyId = approvalTaskEvent.getBizId();
            if (applyId == null) {
                log.warn("[实收审批工作流] 任务事件 bizId 为空，跳过：{}", approvalTaskEvent);
                return;
            }
            Long handlerId = null;
            Map<String, Object> params = approvalTaskEvent.getParams();
            if (params != null) {
                Object h = params.get("handler");
                if (h != null) {
                    try {
                        handlerId = Long.valueOf(h.toString().trim());
                    } catch (NumberFormatException ignore) {
                        // handler 非数字（如系统身份），不留痕
                    }
                }
            }
            log.info("[实收审批工作流] 财务已通过进入总监节点：applyId={}, handlerId={}",
                applyId, handlerId);
            receivedApplyService.stampApproverOnDirectorNode(applyId, handlerId);
        } catch (Exception e) {
            log.error("[实收审批工作流] 回填审批人失败：{}", approvalTaskEvent, e);
        }
    }
}
