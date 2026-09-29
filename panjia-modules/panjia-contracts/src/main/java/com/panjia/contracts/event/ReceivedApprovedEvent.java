package com.panjia.contracts.event;

import lombok.Data;

/**
 * 实收审批通过事件（跨域契约事件，panjia-contracts 叶子模块）。
 * <p>
 * 触发时机：panjia-performance 实收审批单<b>变为 APPROVED</b> 后发布
 * （EventPort.emit，Outbox 原子提交）。覆盖两条路径：贝壳实收导入自动通过直建、
 * 人工审批工作流 finish 回调；历史工资导入直建不发布（已有 LOCKED 建单链路）。
 * <p>
 * 消费方（结佣域）：按合同自动产生结佣记录（DRAFT 申请单 + 各人新签业绩明细，
 * 审批链保留待人工提交）；同合同同期间已有活跃结佣单则跳过。
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

    /** 订单号 */
    private String orderNo;

    /** 合同号 */
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
