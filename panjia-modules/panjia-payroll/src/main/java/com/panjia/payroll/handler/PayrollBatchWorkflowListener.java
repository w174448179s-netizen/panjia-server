package com.panjia.payroll.handler;

import com.panjia.payroll.service.PayrollBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.workflow.api.event.ProcessEvent;
import org.dromara.workflow.api.event.ProcessTaskEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 算薪批次工作流回调监听器（flowCode = payroll_batch）。
 * <p>
 * 实例级 {@link ProcessEvent}：finish（总监锁定节点办理完成）→ 批次 LOCKED +
 * 发布 PayrollLockedEvent；back（总监审核驳回）→ CALCULATED；
 * cancel/invalid/termination → CALCULATED 并解除实例绑定。
 * <p>
 * 任务级 {@link ProcessTaskEvent}：payroll_review 任务创建 → REVIEWING；
 * payroll_lock 任务创建（总监审核已通过）→ APPROVED。
 * <p>
 * 审批动作全部经「我的待办」由引擎按 flow_user 名单判权办理，
 * 不存在业务接口直改状态的业务直批路径。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayrollBatchWorkflowListener {

    private static final String FLOW_CODE = "payroll_batch";
    private static final String NODE_REVIEW = "payroll_review";
    private static final String NODE_LOCK = "payroll_lock";

    private final PayrollBatchService batchService;

    @EventListener(condition = "#processEvent.flowCode == '" + FLOW_CODE + "'")
    public void onProcessEvent(ProcessEvent processEvent) {
        try {
            String businessId = processEvent.getBusinessId();
            if (businessId == null || businessId.isBlank()) {
                log.warn("[薪酬工作流] businessId 为空，跳过：{}", processEvent);
                return;
            }
            Long batchId = Long.valueOf(businessId);
            String handler = null;
            String message = null;
            Map<String, Object> params = processEvent.getParams();
            if (params != null) {
                Object h = params.get("handler");
                Object m = params.get("message");
                handler = h == null ? null : h.toString();
                message = m == null ? null : m.toString();
            }
            log.info("[薪酬工作流] 回调：batchId={}, status={}, nodeCode={}",
                batchId, processEvent.getStatus(), processEvent.getNodeCode());
            batchService.handleWorkflowEvent(batchId, processEvent.getStatus(), handler, message);
        } catch (Exception e) {
            log.error("[薪酬工作流] 回调处理失败：{}", processEvent, e);
        }
    }

    @EventListener(condition = "#processTaskEvent.flowCode == '" + FLOW_CODE
        + "' && (#processTaskEvent.nodeCode == '" + NODE_REVIEW
        + "' || #processTaskEvent.nodeCode == '" + NODE_LOCK + "')")
    public void onTaskNodeCreated(ProcessTaskEvent processTaskEvent) {
        try {
            String businessId = processTaskEvent.getBusinessId();
            if (businessId == null || businessId.isBlank()) {
                log.warn("[薪酬工作流] 任务事件 businessId 为空，跳过：{}", processTaskEvent);
                return;
            }
            Long batchId = Long.valueOf(businessId);
            log.info("[薪酬工作流] 节点任务创建：batchId={}, node={}", batchId, processTaskEvent.getNodeCode());
            batchService.handleTaskNodeEvent(batchId, processTaskEvent.getNodeCode());
        } catch (Exception e) {
            log.error("[薪酬工作流] 节点事件处理失败：{}", processTaskEvent, e);
        }
    }
}
