package com.panjia.contracts.event;

import lombok.Data;

import java.util.Map;

/**
 * 审批任务创建中立事件（任务级）。
 * <p>
 * 由 infrastructure 适配器订阅 Warm-Flow 的 {@code ProcessTaskEvent} 后转译发布。
 * 官方语义：代表任务创建、亦代表上一节点已完成。
 * <p>
 * 典型用途：结佣流程总监办理后进入财务节点，业务侧据此触发实收对齐与
 * 「无差异/全局跳过财务」时的自动完成（设计文档 §3.5、§2.3）。
 */
@Data
public class ApprovalTaskEvent {

    /** 业务类型（{@link com.panjia.contracts.constant.BizType}） */
    private String bizType;
    /** 业务单据 ID */
    private Long bizId;
    /** 当前节点编码 */
    private String nodeCode;
    /** 任务 ID */
    private Long taskId;
    /** 流程实例 ID */
    private Long instanceId;
    /** 办理参数，含 handler（上一节点办理人 ID）/ message 等 */
    private Map<String, Object> params;
}
