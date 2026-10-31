package com.panjia.dingtalk.service.impl;

import com.panjia.contracts.dto.ScoreDetailSyncDTO;
import com.panjia.contracts.port.PeopleScoreSyncPort;
import com.panjia.dingtalk.client.DingTalkContactClient;
import com.panjia.dingtalk.client.DingTalkScoreClient;
import com.panjia.dingtalk.config.DingTalkProperties;
import com.panjia.dingtalk.domain.DingTalkSyncLog;
import com.panjia.dingtalk.dto.DingTalkUserDTO;
import com.panjia.dingtalk.service.DingTalkScoreSyncService;
import com.panjia.dingtalk.service.DingTalkSyncLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 钉钉积分同步实现（积分日报日志 → 积分每日明细 + 月度汇总）。
 * <p>
 * 流程与考勤同步同构：异步执行 + 同步记录按月留痕。
 * 口径与积分导入一致：一次分页拉取各模板（二手/租赁）当月日志，按钉钉 userId
 * 匹配员工工号，同一员工同一天保留提交时间最晚的一条日志（重报视为修正）；
 * 19:30~23:00 为有效窗口，早于 19:30 不计积分，晚于 23:00 记 1 次晚提交。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DingTalkScoreSyncServiceImpl implements DingTalkScoreSyncService {

    /** 有效提交窗口 */
    private static final LocalTime WINDOW_START = LocalTime.of(19, 30);
    private static final LocalTime WINDOW_END = LocalTime.of(23, 0);

    private final DingTalkContactClient contactClient;
    private final DingTalkScoreClient scoreClient;
    private final DingTalkProperties properties;
    private final PeopleScoreSyncPort scoreSyncPort;
    private final DingTalkSyncLogService syncLogService;

    /** 异步执行线程池（手动触发用，避免阻塞 HTTP 请求） */
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "dingtalk-score-async");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public DingTalkSyncLog startAsync(String period, Long operatorId) {
        YearMonth month = parseMonth(period);
        if (syncLogService.hasRunning(DingTalkSyncLogService.TYPE_SCORE, period)) {
            throw new ServiceException("{} 的积分正在同步中，请等当前任务结束后再试", period);
        }
        DingTalkSyncLog entity = syncLogService.start(
            DingTalkSyncLogService.TYPE_SCORE, period,
            DingTalkSyncLogService.TRIGGER_MANUAL, operatorId);
        asyncExecutor.execute(() -> {
            try {
                runSync(entity, period, month);
            } catch (Exception e) {
                // 异步线程内必须兜底，否则日志永远是 RUNNING
                log.error("[钉钉积分同步] {} 异步执行失败：{}", period, e.getMessage(), e);
                syncLogService.finish(entity, DingTalkSyncLogService.STATUS_FAILED, 0, 0, 0, 0,
                    "积分同步失败：" + e.getMessage());
            }
        });
        return entity;
    }

    @Override
    public DingTalkSyncLog syncMonth(String period, String triggerType, Long operatorId) {
        YearMonth month = parseMonth(period);
        DingTalkSyncLog entity = syncLogService.start(
            DingTalkSyncLogService.TYPE_SCORE, period, triggerType, operatorId);
        runSync(entity, period, month);
        return entity;
    }

    /**
     * 实际同步：拉积分日报日志 → 通讯录匹配工号 → 按天聚合 → 写每日明细（汇总由员工域重算）。
     */
    private void runSync(DingTalkSyncLog entity, String period, YearMonth month) {
        try {
            List<DingTalkScoreClient.ScoreLog> logs =
                scoreClient.fetchMonth(properties.getScoreTemplateNames(), properties.getScoreKey(), month);

            Map<String, String> userIdToJobNumber = new LinkedHashMap<>();
            for (DingTalkUserDTO user : contactClient.listAllUsers()) {
                if (StringUtils.isBlank(user.getJobNumber())) {
                    continue;
                }
                userIdToJobNumber.put(user.getUserId(), user.getJobNumber().trim());
            }

            // 同一员工同一天只保留提交时间最晚的一条日志（重报视为修正）
            Map<DailyKey, DailyScore> daily = new LinkedHashMap<>();
            int unmatched = 0;
            for (DingTalkScoreClient.ScoreLog log : logs) {
                String code = userIdToJobNumber.get(log.userId());
                if (code == null) {
                    unmatched++;
                    continue;
                }
                LocalDateTime submitTime = log.submitTime();
                if (submitTime == null) {
                    continue;
                }
                LocalDate date = submitTime.toLocalDate();
                if (date.isBefore(month.atDay(1)) || date.isAfter(month.atEndOfMonth())) {
                    continue;
                }
                DailyScore row = daily.get(new DailyKey(code, date));
                if (row != null && row.submitTime != null && !submitTime.isAfter(row.submitTime)) {
                    continue;
                }
                daily.put(new DailyKey(code, date), new DailyScore(log.score(), submitTime));
            }

            Set<String> employeeCodes = new HashSet<>();
            List<ScoreDetailSyncDTO> details = new ArrayList<>();
            int invalid = 0;
            int late = 0;
            for (Map.Entry<DailyKey, DailyScore> entry : daily.entrySet()) {
                DailyKey key = entry.getKey();
                DailyScore row = entry.getValue();
                boolean inWindow = !row.submitTime.toLocalTime().isBefore(WINDOW_START)
                    && !row.submitTime.toLocalTime().isAfter(WINDOW_END);
                boolean lateSubmit = row.submitTime.toLocalTime().isAfter(WINDOW_END);

                ScoreDetailSyncDTO detail = new ScoreDetailSyncDTO();
                detail.setEmployeeCode(key.employeeCode());
                detail.setPointDate(key.pointDate());
                detail.setSubmitTime(row.submitTime);
                detail.setScore(row.score);
                detail.setValid(inWindow || lateSubmit);
                detail.setLateSubmit(lateSubmit);
                details.add(detail);
                employeeCodes.add(key.employeeCode());
                if (!inWindow && !lateSubmit) {
                    invalid++;
                }
                if (lateSubmit) {
                    late++;
                }
            }

            if (!details.isEmpty()) {
                scoreSyncPort.syncDingTalkScoreDetails(period, details);
            }

            String message = String.format(
                "钉钉积分日报 %d 条；涉及员工 %d 人；每日明细 %d 条；无法匹配工号 %d 条；"
                    + "19:30 前提交无效 %d 条；23:00 后晚提交 %d 条；"
                    + "口径：日志模板 %s，同员工同日保留最晚提交的一条，月度汇总按明细重算",
                logs.size(), employeeCodes.size(), details.size(), unmatched, invalid, late,
                properties.getScoreTemplateNames());
            String status = unmatched > 0
                ? DingTalkSyncLogService.STATUS_PARTIAL : DingTalkSyncLogService.STATUS_SUCCESS;
            syncLogService.finish(entity, status, logs.size(), details.size(), unmatched, 0, message);
            log.info("[钉钉积分同步] {} 完成：{}", period, message);
        } catch (Exception e) {
            String message = "积分同步失败：" + e.getMessage();
            syncLogService.finish(entity, DingTalkSyncLogService.STATUS_FAILED, 0, 0, 0, 0, message);
            log.error("[钉钉积分同步] {} 失败：{}", period, e.getMessage(), e);
            throw new ServiceException(message);
        }
    }

    private YearMonth parseMonth(String period) {
        if (StringUtils.isBlank(period)) {
            throw new ServiceException("积分期间不能为空（格式 YYYY-MM）");
        }
        try {
            return YearMonth.parse(period.trim());
        } catch (DateTimeParseException e) {
            throw new ServiceException("积分期间格式不正确，应为 YYYY-MM：{}", period);
        }
    }

    /** 单人单日积分聚合结果 */
    private static final class DailyScore {
        private final BigDecimal score;
        private final LocalDateTime submitTime;

        private DailyScore(BigDecimal score, LocalDateTime submitTime) {
            this.score = score;
            this.submitTime = submitTime;
        }
    }

    /** 聚合键：工号 + 日期 */
    private record DailyKey(String employeeCode, LocalDate pointDate) {
    }
}
