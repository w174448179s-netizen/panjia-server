package com.panjia.contracts.event;

import lombok.Data;

/**
 * 实收审批通过事件（跨域契约事件，panjia-contracts 叶子模块）。
 * <p>
 * 触发时机：panjia-performance 实收审批单<b>变为 APPROVED</b> 后发布
 * （EventPort.emit，Outbox 原子提交）。覆盖两条路径：贝壳实收导入自动通过直建、
 * 人工审批工作流 finish 回调；历史工资导入直建不发布（已有 LOCKED 建单链路）。
 * <p>
 * 结佣域消费方：审批通过（含系统自动通过与人工终审通过）且业务类型非「二手买卖」时，
 * 按实收月自动发起并提交结佣单；批次撤销重放（{@link #replay}=true）不触发自动发起。
 * <p>
 * 字段可空约定：{@code contractNo} 允许为空（一手房/家装荐客等无合同号业务，
 * 以 {@code orderNo} 为业务锚点），消费方须按「合同号优先、空则订单号」处理。
 * <p>
 * payload 约束（CI: check-event-payload）：基础类型，禁止持有 @Entity。
 */
@Data
public class ReceivedApprovedEvent implements DomainEvent {

    /** 事件类型常量（路由与序列化） */
    public static final String EVENT_TYPE = "received.apply.approved";

    /** 实收审批单 ID（业务主键） */
    private long applyId;

    /** 归属期间 YYYY-MM */
    private String period;

    /** 订单号（无合同号业务的业务锚点） */
    private String orderNo;

    /** 合同号（可空：一手房/家装荐客等无合同号业务为空，消费方回退按 orderNo 处理） */
    private String contractNo;

    /** 审批操作人 ID（自动通过为系统操作人） */
    private Long operatorId;

    /** 业务类型快照（二手买卖等；消费方按类型决定是否自动发起结佣） */
    private String bizType;

    /**
     * 是否系统自动审批通过（仅用于溯源/日志区分，不再决定是否自动发起结佣）。
     * <p>
     * true = 导入分流/新签触发自动通过；false = 人工审批工作流终审通过或批次撤销重放。
     */
    private boolean autoApproved;

    /**
     * 是否批次撤销/恢复的补偿性重放。
     * <p>
     * true = 撤销重放，仅做事件留痕，<b>不触发结佣自动发起</b>（避免撤销后重复建结佣单）；
     * false = 正常审批通过（含人工终审），非「二手买卖」时自动发起结佣。
     */
    private boolean replay;

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public long businessId() {
        return applyId;
    }
}
