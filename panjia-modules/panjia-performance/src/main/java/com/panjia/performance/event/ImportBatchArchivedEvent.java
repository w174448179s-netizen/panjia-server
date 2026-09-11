package com.panjia.performance.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 导入批次归档事件（本地占位类）。
 * <p>
 * 当导入批次完成归档后发布此事件，业绩域监听并触发业绩消费。
 * 后续应迁移至 panjia-contracts 作为跨域领域事件统一管理。
 */
@Getter
public class ImportBatchArchivedEvent extends ApplicationEvent {

    /** 批次 ID */
    private final Long batchId;

    /** 事件 ID（幂等锚点） */
    private final String eventId;

    /** 来源类型 */
    private final String sourceType;

    /** 归属期间（YYYY-MM） */
    private final String period;

    public ImportBatchArchivedEvent(Object source, Long batchId, String eventId,
                                    String sourceType, String period) {
        super(source);
        this.batchId = batchId;
        this.eventId = eventId;
        this.sourceType = sourceType;
        this.period = period;
    }
}
