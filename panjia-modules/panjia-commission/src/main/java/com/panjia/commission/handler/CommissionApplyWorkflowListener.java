package com.panjia.commission.handler;

import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.event.ApprovalEvent;
import com.panjia.contracts.event.ApprovalTaskEvent;
import com.panjia.commission.service.CommissionApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 结佣审批工作流回调监听器（bizType = {@link BizType#COMMISSION}，§3）。
 * <p>
 * 实例级 {@link ApprovalEvent}：finish → 单据 LOCKED + 明细 APPROVED + 发结佣通过事件；
 * back → REJECTED；cancel/invalid/termination → CANCELLED（未审批明细冲销）。
 * <p>
 * 任务级 {@link ApprovalTaskEvent}（语义：代表任务创建、亦代表上一节点已完成）：
 * 流转进入 capp_finance（即总监节点已办理）→ §3.5 实收对齐 +
 * 「无差异/全局跳过财务」时系统自动完成财务节点。总监从「我的待办」原生
 * completeTask 通过同样会走到这里，对齐逻辑不再依赖业务 approve 入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommissionApplyWorkflowListener {

    private static final String NODE_FINANCE = "capp_finance";

    private final CommissionApplicationService applicationService;

    @EventListener(condition = "#approvalEvent.bizType == '" + BizType.COMMISSION + "'")
    public void onApprovalEvent(ApprovalEvent approvalEvent) {
        try {
            Long applicationId = approvalEvent.getBizId();
            if (applicationId == null) {
                log.warn("[结佣工作流] bizId 为空，跳过：{}", approvalEvent);
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
            log.info("[结佣工作流] 回调：applicationId={}, status={}, nodeCode={}",
                applicationId, approvalEvent.getStatus(), approvalEvent.getNodeCode());
            applicationService.handleWorkflowEvent(applicationId, approvalEvent.getStatus(), handler, message);
        } catch (Exception e) {
            log.error("[结佣工作流] 回调处理失败：{}", approvalEvent, e);
        }
    }

    /**
     * 财务节点任务创建 = 总监节点已办理完成：执行 §3.5 联动。
     * <p>params 为上一任务（总监任务）的办理参数，handler 即总监用户 ID，用作对齐操作人。</p>
     */
    @EventListener(condition = "#approvalTaskEvent.bizType == '" + BizType.COMMISSION
        + "' && #approvalTaskEvent.nodeCode == '" + NODE_FINANCE + "'")
    public void onFinanceTaskCreated(ApprovalTaskEvent approvalTaskEvent) {
        try {
            Long applicationId = approvalTaskEvent.getBizId();
            Long taskId = approvalTaskEvent.getTaskId();
            if (applicationId == null || taskId == null) {
                log.warn("[结佣工作流] 财务任务事件缺少 bizId/taskId，跳过：{}", approvalTaskEvent);
                return;
            }
            Long operatorId = null;
            Map<String, Object> params = approvalTaskEvent.getParams();
            if (params != null) {
                Object h = params.get("handler");
                if (h != null) {
                    try {
                        operatorId = Long.valueOf(h.toString().trim());
                    } catch (NumberFormatException ignore) {
                        // handler 非数字（如系统身份），对齐操作人置空
                    }
                }
            }
            log.info("[结佣工作流] 总监已通过，进入财务节点联动：applicationId={}, taskId={}",
                applicationId, taskId);
            applicationService.afterDirectorPassed(applicationId, taskId, operatorId);
        } catch (Exception e) {
            log.error("[结佣工作流] 财务节点联动处理失败：{}", approvalTaskEvent, e);
        }
    }
}
