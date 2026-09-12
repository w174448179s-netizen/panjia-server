package com.panjia.contracts.event;

import lombok.Data;

import java.util.List;

/**
 * 业绩事实冲销事件（跨域契约事件，panjia-contracts 叶子模块）。
 * <p>
 * 触发时机：业绩域发生事实冲销后由 panjia-performance 发布（EventPort.emit，与冲销事务原子提交）。
 * 覆盖三类冲销来源：批次替换 supersede（SUPERSEDE）/ 重归一化（RENORMALIZE）/
 * 人工调整或期间作废（MANUAL_ADJUST / PERIOD_VOID）。
 * <p>
 * 消费方（结佣域）按分治表处理（结佣域详细设计 §4.4）：
 * <ul>
 *   <li>PENDING 明细 → 随动作废（REVERSED）；</li>
 *   <li>APPROVED 明细 → 金额一动不动，仅置 origin_reversed = true + 告警（V4.2 §9.2-4）；</li>
 *   <li>REVERSED 明细 → 忽略。</li>
 * </ul>
 * <p>
 * payload 约束（CI: check-event-payload）：基础类型 / List&lt;String&gt;，禁止持有 @Entity。
 */
@Data
public class PerformanceFactReversedEvent implements DomainEvent {

    /** 事件类型常量（路由与序列化） */
    public static final String EVENT_TYPE = "performance.fact.reversed";

    /** 归属期间 YYYY-MM（可空：跨期间冲销时以明细自身 period 为准） */
    private String period;

    /** 被冲销的事实 ID 列表（JSON 序列化兼容性用 String） */
    private List<String> factIds;

    /** 冲销原因（ReversedReason code：SUPERSEDE / RENORMALIZE / MANUAL_ADJUST / PERIOD_VOID） */
    private String reason;

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public long businessId() {
        if (factIds == null || factIds.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(factIds.get(0));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
