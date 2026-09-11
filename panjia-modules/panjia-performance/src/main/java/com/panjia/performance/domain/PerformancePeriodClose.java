package com.panjia.performance.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 期间封账（对应 pj_perf_period_close 表）。
 * <p>
 * 按期间（YYYY-MM）管理业绩数据的封账状态，封账后该期间的业绩事实
 * 不再允许新增、修改或冲销。期间字段唯一，每月最多一条封账记录。
 * <p>
 * 不继承 RuoYi BaseEntity：本表无 create_by/update_by 审计列，
 * create_time/update_time 由数据库默认值填充。
 */
@Data
@TableName("pj_perf_period_close")
public class PerformancePeriodClose implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** ID（雪花 ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 期间 YYYY-MM（唯一） */
    private String period;

    /** 状态：OPEN-开启 / CLOSED-已封账 */
    private PeriodCloseStatus status;

    /** 封账原因 */
    private String closeReason;

    /** 关联薪资核算批次 */
    private Long refBatchId;

    /** 操作人 ID */
    private Long operatorId;

    /** 封账时间 */
    private LocalDateTime closeTime;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;

    /** 更新时间（DB 默认填充） */
    private LocalDateTime updateTime;
}
