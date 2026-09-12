package com.panjia.commission.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 结佣消费日志（对应 pj_commission_consume_log 表）。
 * <p>
 * 消费业绩域事件，做幂等锚点（event_id 唯一索引 uk_ccl_event）+ 冲销联动留痕（§3.4）。
 */
@Data
@TableName("pj_commission_consume_log")
public class CommissionConsumeLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 事件 ID（幂等锚点，唯一） */
    private String eventId;

    /** 事件类型 FACT_CREATED / FACT_REVERSED / INCREMENTAL_REFRESH */
    private String eventType;

    /** 归属期间 YYYY-MM */
    private String period;

    /** 事件携带的事实 ID 列表（逗号分隔，留痕） */
    private String factIds;

    /** 事件携带的事实数 */
    private Integer factCount;

    /** 受影响的结佣明细数 */
    private Integer affectedItems;

    /** 消费状态 SUCCESS/PARTIAL/FAILED */
    private ConsumeStatus status;

    /** 备注 / 失败原因 */
    private String message;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;

    /** 更新时间（DB 默认填充） */
    private LocalDateTime updateTime;
}
