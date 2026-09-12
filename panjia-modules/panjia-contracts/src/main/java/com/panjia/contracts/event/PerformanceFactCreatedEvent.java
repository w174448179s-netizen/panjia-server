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

    /**
     * 本批是否含退单红冲事实（负数行，reversal_type=REDINK_REFUND）。
     * <p>
     * 下游（结佣/算薪）据此识别：红冲事实的金额镜像自成交月原事实的冻结口径，
     * 计算扣回时必须沿 {@link #refundOfFactIds} 找原事实/原规则快照，禁止按当期职级提点重算。
     */
    private boolean refundRedink;

    /**
     * 红冲溯源链：与 {@link #factIds} 下标对齐，每个红冲事实对应其镜像的原正数事实 ID；
     * 非红冲位置为 null。仅在 {@link #refundRedink}=true 时填充。
     */
    private List<String> refundOfFactIds;

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public long businessId() {
        return batchId;
    }
}
