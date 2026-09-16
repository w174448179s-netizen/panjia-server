package com.panjia.contracts.event;

import lombok.Data;

import java.util.Map;

/**
 * 审批状态变更中立事件。
 * <p>
 * 由 infrastructure 适配器订阅 Warm-Flow 的 {@code ProcessEvent} 后转译发布，
 * 业务监听器改订阅本事件，彻底切断对 {@code org.dromara.workflow} 的编译期依赖。
 * <p>
 * 设计依据：《审批集成设计说明 V1.0》§五（依赖隔离）+ §四（状态归属，监听器回写）。
 * <p>
 * 幂等约定：业务监听器按 {@code bizId + 目标状态} 做状态机前置校验，
 * 因本事件可能被重复触发（重试、补偿、消息重投）。
 */
@Data
public class ApprovalEvent {

    /** 业务类型（{@link com.panjia.contracts.constant.BizType}） */
    private String bizType;
    /** 业务单据 ID */
    private Long bizId;
    /**
     * 流程状态，对应 Warm-Flow 的 ProcessEvent.status：
     * finish（审批通过）/ back（驳回）/ cancel（撤销）/ invalid（作废）/ termination（终止）。
     */
    private String status;
    /** 当前节点编码 */
    private String nodeCode;
    /** 办理参数，含 handler（办理人 ID）/ message（审批意见）等 */
    private Map<String, Object> params;
}
