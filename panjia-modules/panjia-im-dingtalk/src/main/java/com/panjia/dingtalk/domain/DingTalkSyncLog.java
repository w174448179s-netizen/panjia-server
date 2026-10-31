package com.panjia.dingtalk.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 钉钉同步日志（一次同步一行，记录结果与字段缺口）。
 */
@Data
@TableName("pj_dingtalk_sync_log")
public class DingTalkSyncLog {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 同步类型：ATTENDANCE */
    private String syncType;

    /** 考勤期间（YYYY-MM） */
    private String period;

    /** 触发方式：MANUAL / SCHEDULE */
    private String triggerType;

    /** 状态：RUNNING / SUCCESS / PARTIAL / FAILED */
    private String status;

    /** 钉钉侧记录数 */
    private Integer totalCount;

    /** 成功条数 */
    private Integer successCount;

    /** 跳过条数（工号未匹配等） */
    private Integer skippedCount;

    /** 失败条数 */
    private Integer failedCount;

    /** 结果说明 / 字段缺口 / 错误摘要 */
    private String message;

    /** 手动触发人 ID */
    private Long operatorId;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;
}
