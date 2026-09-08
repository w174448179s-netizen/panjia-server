package com.panjia.outbox.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Outbox 幂等记录实体（对应 pj_outbox_idempotent 表）。
 * <p>
 * event_id 复用 pj_event_outbox.event_id（唯一幂等键）。
 * 消费前 INSERT，主键冲突 = 已消费 = 幂等跳过。
 */
@Data
@TableName("pj_outbox_idempotent")
public class OutboxIdempotent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 事件 ID（主键，复用 outbox.event_id） */
    @TableId
    private String eventId;

    /** 消费时间 */
    private LocalDateTime consumedAt;
}
