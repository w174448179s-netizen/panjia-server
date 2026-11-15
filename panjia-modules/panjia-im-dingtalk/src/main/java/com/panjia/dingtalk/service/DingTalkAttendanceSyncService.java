package com.panjia.dingtalk.service;

import com.panjia.dingtalk.domain.DingTalkSyncLog;

/**
 * 钉钉考勤同步服务。
 */
public interface DingTalkAttendanceSyncService {

    /**
     * 手动触发异步同步：立即返回 RUNNING 日志，后台线程执行。
     * <p>
     * 同月已有 RUNNING 记录时拒绝，避免重复跑。
     *
     * @param period     YYYY-MM
     * @param operatorId 手动触发人 ID
     * @return 同步日志（RUNNING 状态，前端轮询进度）
     */
    DingTalkSyncLog startAsync(String period, Long operatorId);

    /**
     * 同步指定月份的钉钉考勤（阻塞执行，供定时任务调用）。
     *
     * @param period      YYYY-MM
     * @param triggerType 触发方式（MANUAL / SCHEDULE）
     * @param operatorId  手动触发人 ID（定时任务为 null）
     * @return 同步日志（含统计与字段缺口说明）
     */
    DingTalkSyncLog syncMonth(String period, String triggerType, Long operatorId);
}
