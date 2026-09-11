package com.panjia.contracts.event;

import lombok.Data;

/**
 * 导入批次重归一化事件（跨域契约事件，panjia-contracts 叶子模块，CR-5）。
 * <p>
 * 触发时机：导入批次执行重归一化（{@code renormalize}，PENDING_CONFIRM → NORMALIZING → ARCHIVED/PENDING_CONFIRM）
 * 完成归档后由 panjia-import 域发布。
 * <p>
 * 与 {@link ImportBatchArchivedEvent} 的区别：
 * <ul>
 *   <li>ArchivedEvent 通常携带 supersededBatchIds（其他批次被本批 supersede 的）</li>
 *   <li>RenormalizedEvent 触发的是「本批次自身的」冲销重建（reversed_reason = RENORMALIZE），
 *       不存在跨批次 supersede 关系</li>
 *   <li>下游共用同一个 ReverseService，仅 reversed_reason 取值不同（审计可区分）</li>
 * </ul>
 * payload 字段约束：基础类型 / Long / String，禁止持有 @Entity。
 */
@Data
public class ImportBatchRenormalizedEvent implements DomainEvent {

    /** 事件类型常量（路由与序列化） */
    public static final String EVENT_TYPE = "import.batch.renormalized";

    /** 批次 ID（业务主键） */
    private long batchId;

    /** 来源类型（ImportSourceType code） */
    private String sourceType;

    /** 归属期间（YYYY-MM） */
    private String period;

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public long businessId() {
        return batchId;
    }
}