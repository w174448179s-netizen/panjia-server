package com.panjia.infrastructure.adapter.workflow;

import com.panjia.contracts.event.ApprovalEvent;
import com.panjia.contracts.event.ApprovalTaskEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.workflow.api.event.ProcessEvent;
import org.dromara.workflow.api.event.ProcessTaskEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Warm-Flow 事件转译器：订阅引擎原生 {@link ProcessEvent} / {@link ProcessTaskEvent}，
 * 按 flowCode 逆映射为 bizType 后转译为中立事件 {@link ApprovalEvent} / {@link ApprovalTaskEvent} 发布。
 * <p>
 * 业务监听器改订阅中立事件，彻底切断对 {@code org.dromara.workflow} 的编译期依赖。
 * <p>
 * 只做转发，不含业务规则。未配置 flowCode 映射的流程事件直接丢弃（避免误触发业务监听器）。
 * <p>
 * 设计依据：《审批集成设计说明 V1.0》§五（依赖隔离）+ §四（状态归属，监听器回写）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WarmFlowEventTranslator {

    private final ApplicationEventPublisher publisher;

    /**
     * 实例级事件：finish / back / cancel / invalid / termination。
     */
    @EventListener
    public void onProcessEvent(ProcessEvent event) {
        String bizType = FlowCodeMapping.toBizType(event.getFlowCode());
        if (bizType == null) {
            // 非盘家业务的流程事件，忽略
            return;
        }
        String businessId = event.getBusinessId();
        if (StringUtils.isBlank(businessId)) {
            log.warn("[事件转译] ProcessEvent businessId 为空，丢弃：flowCode={}, instanceId={}",
                event.getFlowCode(), event.getInstanceId());
            return;
        }
        ApprovalEvent approval = new ApprovalEvent();
        approval.setBizType(bizType);
        approval.setBizId(Long.valueOf(businessId));
        approval.setStatus(event.getStatus());
        approval.setNodeCode(event.getNodeCode());
        approval.setParams(event.getParams());
        publisher.publishEvent(approval);
    }

    /**
     * 任务级事件：节点任务创建（上一节点已完成）。
     */
    @EventListener
    public void onProcessTaskEvent(ProcessTaskEvent event) {
        String bizType = FlowCodeMapping.toBizType(event.getFlowCode());
        if (bizType == null) {
            return;
        }
        String businessId = event.getBusinessId();
        if (StringUtils.isBlank(businessId) || event.getTaskId() == null) {
            log.warn("[事件转译] ProcessTaskEvent 缺少 businessId/taskId，丢弃：flowCode={}, taskId={}",
                event.getFlowCode(), event.getTaskId());
            return;
        }
        ApprovalTaskEvent approval = new ApprovalTaskEvent();
        approval.setBizType(bizType);
        approval.setBizId(Long.valueOf(businessId));
        approval.setNodeCode(event.getNodeCode());
        approval.setTaskId(event.getTaskId());
        approval.setInstanceId(event.getInstanceId());
        approval.setParams(event.getParams());
        publisher.publishEvent(approval);
    }
}
