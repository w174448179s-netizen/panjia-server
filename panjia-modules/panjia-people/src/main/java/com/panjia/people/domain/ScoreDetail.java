package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 积分每日明细（一员工一天一行）。
 * <p>
 * 来源：积分日报导入同步（ScoreArchiveHandler 消费归档事件时，
 * ScoreService.syncScoreDetails 按 (员工, 日期) 覆盖写入），无人工编辑入口。
 * 跨批次（如二手 + 租赁同月）按日期合并，同一天后导入者覆盖。
 */
@Data
@TableName("pj_people_score_detail")
public class ScoreDetail implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long employeeId;

    /** 积分月份（当月 1 日，冗余便于按月清理） */
    private LocalDate scoreMonth;

    /** 填报日期 */
    private LocalDate pointDate;

    /** 填报时间（含时分秒） */
    private LocalDateTime submitTime;

    /** 当日积分 */
    private BigDecimal score;

    /** 是否计入总积分（窗口外提交为 false，当日积分按 0 存） */
    private Boolean isValid;

    /** 是否晚提交（积分日期 23:00 ~ 次日 09:00 提交为 true） */
    private Boolean isLateSubmit;

    /** 写入该行的导入批次 ID（整批替换/撤销时按此精确清理） */
    private Long sourceBatchId;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
