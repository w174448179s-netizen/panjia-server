package com.panjia.contracts.event;

import lombok.Data;

/**
 * 实收审批通过事件（跨域契约事件，panjia-contracts 叶子模块）。
 * <p>
 * 触发时机：panjia-performance 实收审批单<b>变为 APPROVED</b> 后发布
 * （EventPort.emit，Outbox 原子提交）。覆盖两条路径：贝壳实收导入自动通过直建、
 * 人工审批工作流 finish 回调；历史工资导入直建不发布（已有 LOCKED 建单链路）。
 * <p>
 * 结佣域消费方尚未接入自动建单；结佣单当前由业务人员在结佣列表对 APPROVED 实收
 * 手工发起（§3.2 前置校验），事件保留审批留痕并供批次撤销/冲正链路重放。
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

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public long businessId() {
        return applyId;
    }
}
