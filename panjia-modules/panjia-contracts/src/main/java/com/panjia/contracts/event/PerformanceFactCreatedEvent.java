package com.panjia.contracts.event;

import lombok.Data;

import java.util.List;

/**
 * 业绩事实创建事件（跨域契约事件，panjia-contracts 叶子模块）。
 * <p>
 * 触发时机：业绩域消费导入批次、在<b>同一业务事务内</b>成功新建事实后由 panjia-performance 发布
 * （EventPort.emit，Outbox 原子提交）。
 * <p>
 * 消费方（结佣域）：仅 {@code PERF_REAL} 触发——若该 (period, deptId) 存在 DRAFT/SUBMITTED
 * 申请单则提示算薪人员执行增量重拉；<b>不自动创建明细</b>（进工资必须经人工发起 + 审批）。
 * {@code PERF_EXPECT} 忽略（新签透传不走结佣明细）。
 * <p>
 * payload 约束（CI: check-event-payload）：基础类型 / List&lt;String&gt;，禁止持有 @Entity。
 */
@Data
public class PerformanceFactCreatedEvent implements DomainEvent {

    /** 事件类型常量（路由与序列化） */
    public static final String EVENT_TYPE = "performance.fact.created";

    /** 来源导入批次 ID（业务主键） */
    private long batchId;

    /** 归属期间 YYYY-MM */
    private String period;

    /** 事实口径（FactType code：PERF_REAL / PERF_EXPECT，本批新建事实同口径） */
    private String factType;

    /** 本批新建的事实 ID 列表（JSON 序列化兼容性用 String） */
    private List<String> factIds;

    /** 本批事实覆盖的门店 ID 列表（去重，JSON 序列化用 String） */
    private List<String> deptIds;

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public long businessId() {
        return batchId;
    }
}
