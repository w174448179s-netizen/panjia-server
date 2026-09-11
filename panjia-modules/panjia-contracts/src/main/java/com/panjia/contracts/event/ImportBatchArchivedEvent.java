package com.panjia.contracts.event;

import lombok.Data;

import java.util.List;

/**
 * 导入批次归档事件（跨域契约事件，panjia-contracts 叶子模块）。
 * <p>
 * 触发时机：导入批次进入终态 ARCHIVED 时由 panjia-import 域发布。
 * <p>
 * payload 字段约束（CI: check-event-payload 校验）：
 * <ul>
 *   <li>基础类型 / Long / String / 不可变 POJO / List&lt;Long&gt;（不允许 @Entity）</li>
 *   <li>{@code batchId} / {@code sourceType} / {@code period} 业务主键</li>
 *   <li>{@code supersededBatchIds}（CR-1）：本次归档触发的"被废弃旧批次 ID 列表"，
 *       下游（业绩域）依此冲销旧事实，避免新旧业绩并存导致提成重复计算（§4.2）</li>
 * </ul>
 * <p>
 * 依赖方向：定义在 panjia-contracts 叶子模块，业务域单向依赖该事件（panjia-performance
 * 通过 DomainEventHandler 消费），panjia-import 只依赖 contracts 而非 performance
 * —— 消除原"事件定义在 panjia-performance 反向依赖"的问题（CR-1）。
 */
@Data
public class ImportBatchArchivedEvent implements DomainEvent {

    /** 事件类型常量（路由与序列化） */
    public static final String EVENT_TYPE = "import.batch.archived";

    /** 批次 ID（业务主键） */
    private long batchId;

    /** 来源类型（ImportSourceType code，如 KE_SIGNED / ATTENDANCE） */
    private String sourceType;

    /** 归属期间（YYYY-MM） */
    private String period;

    /**
     * 被本批 supersede 的旧批次 ID 列表。
     * <p>
     * 语义：本次归档触发时，按 V2.0 §5.5 SUPERSEDED 机制，
     * 同 (sourceType, period, deptId) 唯一索引范围内被本批废弃的旧批次 ID 集合。
     * 业绩域收到事件后，对列表中每个旧批次的事实标记 REVERSED（reversed_reason = SUPERSEDE）。
     * 空集合表示本次未触发 supersede（如首次导入）。
     */
    private List<String> supersededBatchIds;

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public long businessId() {
        return batchId;
    }
}