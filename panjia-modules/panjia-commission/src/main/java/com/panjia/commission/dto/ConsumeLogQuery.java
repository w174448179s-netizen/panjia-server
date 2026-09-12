package com.panjia.commission.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

/**
 * 结佣消费日志查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class ConsumeLogQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 归属期间（YYYY-MM） */
    private String period;

    /** 事件类型（FACT_CREATED / FACT_REVERSED / INCREMENTAL_REFRESH） */
    private String eventType;

    /** 事件 ID */
    private String eventId;
}
