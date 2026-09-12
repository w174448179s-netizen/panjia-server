package com.panjia.contracts.event;

import lombok.Data;

import java.util.List;

/**
 * 结佣审批通过事件（跨域契约事件，panjia-contracts 叶子模块）。
 * <p>
 * 触发时机：结佣申请单审批回调通过、状态锁定（LOCKED）的同事务内由 panjia-commission 发布。
 * <p>
 * 消费方（payroll 域）：按 approvedMonth（工资归属月）拉取锁定明细参与算薪。
 * 事件只做通知、只传 ID，明细数据由 payroll 经 {@code CommissionQueryPort} 拉取，
 * 避免消息体过大（CI C14）。
 * <p>
 * payload 约束（CI: check-event-payload）：基础类型 / List&lt;String&gt;，禁止持有 @Entity。
 */
@Data
public class CommissionApprovedEvent implements DomainEvent {

    /** 事件类型常量（路由与序列化） */
    public static final String EVENT_TYPE = "commission.approved";

    /** 申请单 ID（业务主键） */
    private long applicationId;

    /** 业绩归属月（结算月 YYYY-MM） */
    private String period;

    /** 工资归属月（审批通过月 YYYY-MM，V4.2 硬要求 1） */
    private String approvedMonth;

    /** 门店 ID */
    private Long deptId;

    /** 本次锁定的结佣明细 ID 列表（JSON 序列化兼容性用 String） */
    private List<String> itemIds;

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public long businessId() {
        return applicationId;
    }
}
