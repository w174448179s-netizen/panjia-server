package com.panjia.dingtalk.task;

import com.panjia.dingtalk.service.DingTalkAttendanceSyncService;
import com.panjia.dingtalk.service.DingTalkSyncLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.redis.utils.RedisUtils;
import org.redisson.api.RLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 钉钉考勤每日定时同步。
 * <p>
 * 默认每晚 23:30 执行一次（可通过 panjia.dingtalk.sync-cron 调整），同步当月考勤；
 * 每月 1 日额外补同步上一个月，避免月末最后几小时数据遗漏。
 * 人员同步只在「基础档案 → 钉钉同步」页面手动触发，不参与定时。
 * 多实例集群通过 Redisson 锁保证同一时刻只有一个实例执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "panjia.dingtalk", name = "enabled", havingValue = "true")
public class DingTalkSyncScheduleTask {

    private static final String LOCK_DAILY = "panjia:dingtalk:sync:daily";

    private final DingTalkAttendanceSyncService attendanceSyncService;

    /**
     * 每晚同步当月考勤（每月 1 日补上月）。
     */
    @Scheduled(cron = "${panjia.dingtalk.sync-cron:0 30 23 * * ?}")
    public void syncDaily() {
        RLock lock = RedisUtils.getClient().getLock(LOCK_DAILY);
        if (!lock.tryLock()) {
            log.info("[钉钉定时同步] 已由其他实例执行，本实例跳过");
            return;
        }
        try {
            String currentMonth = YearMonth.now().toString();
            syncAttendanceQuietly(currentMonth);
            // 每月 1 日补同步上月（月末最后时段的打卡在当月最后一次同步时可能还没产生）
            if (LocalDate.now().getDayOfMonth() == 1) {
                syncAttendanceQuietly(YearMonth.now().minusMonths(1).toString());
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void syncAttendanceQuietly(String period) {
        try {
            attendanceSyncService.syncMonth(period, DingTalkSyncLogService.TRIGGER_SCHEDULE, null);
        } catch (Exception e) {
            log.error("[钉钉定时同步] {} 考勤同步失败：{}", period, e.getMessage(), e);
        }
    }
}
