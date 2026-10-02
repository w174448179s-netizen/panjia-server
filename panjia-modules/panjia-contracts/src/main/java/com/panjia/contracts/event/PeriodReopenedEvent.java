package com.panjia.contracts.event;

import lombok.Data;

/**
 * 业绩期间解封（反结账）事件（跨域契约事件，panjia-contracts 叶子模块）。
 * <p>
 * 触发时机：{@code panjia-performance} 期间由 CLOSED 反结账为 OPEN 时，
 * 在<b>同一业务事务内</b>经 {@code ApplicationEventPublisher} 同步发布。
 * <p>
 * 消费方：
 * <ul>
 *   <li>{@code panjia-payroll}：把该期间所有 LOCKED 状态的工资批次解锁回 CALCULATED，
 *       清空 processInstanceId（流程实例已随 finish 结束）与锁定人/锁定时间，
 *       使批次可重新算薪 → 重新提交审批 → 再次锁定。解封与解锁必须同事务，
 *       避免出现"期间已开但工资仍锁"的半残状态（方案 B：解封联动解锁）。</li>
 * </ul>
 * <p>
 * payload 约束：基础类型 / 不可变 POJO，禁止持有 @Entity。
 */
@Data
public class PeriodReopenedEvent implements DomainEvent {

    /** 事件类型常量（路由与序列化） */
    public static final String EVENT_TYPE = "period.reopened";

    /** 事件唯一标识（UUID，消费方幂等用） */
    private String eventId;

    /** 解封的业绩期间 YYYY-MM（也是工资归属月） */
    private String period;

    /** 反结账原因（留痕审计） */
    private String reason;

    /** 执行解封的操作人 ID */
    private Long operatorId;

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public long businessId() {
        // 期间无数字主键，用期间字符串的 hashCode 作为业务 ID（仅用于事件路由审计）
        return period == null ? 0L : period.hashCode();
    }
}
