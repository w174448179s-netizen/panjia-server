package com.panjia.performance.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 业绩消费日志（对应 pj_perf_consume_log 表）。
 * <p>
 * 记录业绩事实的消费处理过程，包括导入批次消费、事件消费等，
 * 支持按事件 ID 幂等去重。
 * <p>
 * 不继承 RuoYi BaseEntity：本表无 create_by/update_by 审计列，
 * create_time/update_time 由数据库默认值填充。
 */
@Data
@TableName("pj_perf_consume_log")
public class PerformanceConsumeLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 日志 ID（雪花 ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 导入批次 ID */
    private Long batchId;

    /** 事件类型 */
    private String eventType;

    /** 事件 ID（幂等锚点） */
    private String eventId;

    /** 归属期间 YYYY-MM */
    private String period;

    /** 来源类型 */
    private String sourceType;

    /** 消费状态：RUNNING-进行中 / SUCCESS-成功 / PARTIAL-部分成功 / FAILED-失败 */
    private ConsumeStatus status;

    /** 总记录数 */
    private Integer totalRows;

    /** 成功数 */
    private Integer successRows;

    /** 失败数 */
    private Integer failedRows;

    /** 错误信息/备注 */
    private String message;

    /** 操作人 ID */
    private Long operatorId;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;

    /** 更新时间（DB 默认填充） */
    private LocalDateTime updateTime;
}
