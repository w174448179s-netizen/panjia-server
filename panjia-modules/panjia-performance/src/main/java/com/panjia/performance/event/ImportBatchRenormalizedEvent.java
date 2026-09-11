package com.panjia.performance.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 导入批次重归一化事件（本地占位类）。
 * <p>
 * 当导入批次重新归一化后发布此事件，业绩域监听后先冲销旧事实再重新生成业绩。
 * 后续应迁移至 panjia-contracts 作为跨域领域事件统一管理。
 */
@Getter
public class ImportBatchRenormalizedEvent extends ApplicationEvent {

    /** 批次 ID */
    private final Long batchId;

    /** 事件 ID（幂等锚点） */
    private final String eventId;

    /** 来源类型 */
    private final String sourceType;

    /** 归属期间（YYYY-MM） */
    private final String period;

    public ImportBatchRenormalizedEvent(Object source, Long batchId, String eventId,
                                        String sourceType, String period) {
        super(source);
        this.batchId = batchId;
        this.eventId = eventId;
        this.sourceType = sourceType;
        this.period = period;
    }
}
