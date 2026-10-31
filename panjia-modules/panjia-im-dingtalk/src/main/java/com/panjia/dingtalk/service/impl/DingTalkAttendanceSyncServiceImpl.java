package com.panjia.dingtalk.service.impl;

import com.panjia.contracts.dto.AttendanceDetailSyncDTO;
import com.panjia.contracts.dto.AttendanceSummarySyncDTO;
import com.panjia.contracts.port.PeopleAttendanceSyncPort;
import com.panjia.dingtalk.client.DingTalkAttendanceClient;
import com.panjia.dingtalk.client.DingTalkContactClient;
import com.panjia.dingtalk.config.DingTalkConfigProvider;
import com.panjia.dingtalk.domain.DingTalkSyncLog;
import com.panjia.dingtalk.dto.DingTalkUserDTO;
import com.panjia.dingtalk.service.DingTalkAttendanceSyncService;
import com.panjia.dingtalk.service.DingTalkSyncLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 钉钉考勤同步实现（钉钉智能考勤报表 → 月度汇总 + 每日明细）。
 * <p>
 * 口径与钉钉「月度汇总」一致：出勤天数、休息天数、迟到次数/时长、上下班缺卡、旷工天数
 * 直接取报表列；每日明细取「考勤结果」列（正常/迟到/缺卡/旷工/请假/休息原文）；
 * 事假/病假按假期名称取每日时长求和（名称可在配置中调整）。
 * <p>
 * 手动触发为异步任务：接口立即返回 RUNNING 日志，前端轮询记录状态；
 * 同步记录落在 {@code pj_dingtalk_sync_log}，按月可查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DingTalkAttendanceSyncServiceImpl implements DingTalkAttendanceSyncService {

    /** 报表列 alias */
    private static final String COL_ATTEND_DAYS = "attendance_days";
    private static final String COL_REST_DAYS = "attendance_rest_days";
    private static final String COL_LATE_TIMES = "late_times";
    private static final String COL_LATE_MINUTE = "late_minute";
    private static final String COL_LACK_ON_DUTY = "on_work_lack_card_times";
    private static final String COL_LACK_OFF_DUTY = "off_work_lack_card_times";
    private static final String COL_ABSENT_DAYS = "absenteeism_days";

    /** 汇总数值列（一次请求取回） */
    private static final List<String> SUMMARY_COLUMNS = List.of(
        COL_ATTEND_DAYS, COL_REST_DAYS, COL_LATE_TIMES, COL_LATE_MINUTE,
        COL_LACK_ON_DUTY, COL_LACK_OFF_DUTY, COL_ABSENT_DAYS);

    private final DingTalkContactClient contactClient;
    private final DingTalkAttendanceClient attendanceClient;
    private final PeopleAttendanceSyncPort attendanceSyncPort;
    private final DingTalkSyncLogService syncLogService;
    private final DingTalkConfigProvider configProvider;

    /** 异步执行线程池（手动触发用，避免阻塞 HTTP 请求） */
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "dingtalk-attendance-async");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public DingTalkSyncLog startAsync(String period, Long operatorId) {
        YearMonth month = parseMonth(period);
        if (syncLogService.hasRunning(DingTalkSyncLogService.TYPE_ATTENDANCE, period)) {
            throw new ServiceException("{} 的考勤正在同步中，请等当前任务结束后再试", period);
        }
        DingTalkSyncLog entity = syncLogService.start(
            DingTalkSyncLogService.TYPE_ATTENDANCE, period,
            DingTalkSyncLogService.TRIGGER_MANUAL, operatorId);
        asyncExecutor.execute(() -> {
            try {
                runSync(entity, period, month);
            } catch (Exception e) {
                // 异步线程内必须兜底，否则日志永远是 RUNNING
                log.error("[钉钉考勤同步] {} 异步执行失败：{}", period, e.getMessage(), e);
                syncLogService.finish(entity, DingTalkSyncLogService.STATUS_FAILED, 0, 0, 0, 0,
                    "考勤同步失败：" + e.getMessage());
            }
        });
        return entity;
    }

    @Override
    public DingTalkSyncLog syncMonth(String period, String triggerType, Long operatorId) {
        YearMonth month = parseMonth(period);
        DingTalkSyncLog entity = syncLogService.start(
            DingTalkSyncLogService.TYPE_ATTENDANCE, period, triggerType, operatorId);
        runSync(entity, period, month);
        return entity;
    }

    /**
     * 实际同步：拉通讯录 → 并发取每人报表数据 → 写汇总 + 每日明细 → 回写日志。
     */
    private void runSync(DingTalkSyncLog entity, String period, YearMonth month) {
        try {
            List<DingTalkUserDTO> users = contactClient.listAllUsers();
            Map<String, String> userIdToJobNumber = new LinkedHashMap<>();
            int noJobNumber = 0;
            Set<String> noJobNumberNames = new LinkedHashSet<>();
            for (DingTalkUserDTO user : users) {
                if (StringUtils.isBlank(user.getJobNumber())) {
                    noJobNumber++;
                    if (noJobNumberNames.size() < 20) {
                        noJobNumberNames.add(StringUtils.isBlank(user.getName())
                            ? user.getUserId() : user.getName());
                    }
                    continue;
                }
                userIdToJobNumber.put(user.getUserId(), user.getJobNumber().trim());
            }

            List<AttendanceSummarySyncDTO> summaries = new ArrayList<>();
            List<AttendanceDetailSyncDTO> details = new ArrayList<>();
            int failedUsers = 0;
            String firstError = null;

            // 假期名称一次读取后传入并发任务，避免每个线程重复读参数缓存
            String leavePersonal = configProvider.leaveNamePersonal();
            String leaveSick = configProvider.leaveNameSick();

            // 逐人取数要 2 次报表请求，串行在人多时会很慢；4 并发取数（规避钉钉限流）
            ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
                Thread thread = new Thread(r, "dingtalk-attendance-sync");
                thread.setDaemon(true);
                return thread;
            });
            try {
                Map<String, Future<UserRow>> futures = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : userIdToJobNumber.entrySet()) {
                    String userId = entry.getKey();
                    String jobNumber = entry.getValue();
                    futures.put(userId, pool.submit(() -> fetchUser(jobNumber, userId, month, leavePersonal, leaveSick)));
                }
                int done = 0;
                for (Map.Entry<String, Future<UserRow>> entry : futures.entrySet()) {
                    try {
                        UserRow row = entry.getValue().get();
                        summaries.add(row.summary());
                        details.addAll(row.details());
                    } catch (Exception e) {
                        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                        failedUsers++;
                        if (firstError == null) {
                            firstError = cause.getMessage();
                        }
                        log.warn("[钉钉考勤同步] userid={} 取数失败：{}", entry.getKey(), cause.getMessage());
                    }
                    done++;
                    if (done % 10 == 0) {
                        log.info("[钉钉考勤同步] {} 进度 {}/{}", period, done, userIdToJobNumber.size());
                    }
                }
            } finally {
                pool.shutdownNow();
            }

            if (!summaries.isEmpty()) {
                attendanceSyncPort.syncAttendanceSummaries(period, summaries);
            }
            if (!details.isEmpty()) {
                attendanceSyncPort.syncAttendanceDetails(period, details);
            }

            String message = String.format(
                "钉钉人员 %d 人；月度汇总 %d 条；每日明细 %d 条；无工号跳过 %d 人；取数/同步失败 %d 人%s；"
                    + "口径：钉钉智能考勤报表（事假=%s，病假=%s 记入诊所类）",
                users.size(), summaries.size(), details.size(), noJobNumber, failedUsers,
                firstError == null ? "" : "（首个错误：" + firstError + "）",
                leavePersonal, leaveSick);
            if (!noJobNumberNames.isEmpty()) {
                message = message + "；无工号人员（最多列 20 个）：" + String.join("、", noJobNumberNames);
            }
            String status = (noJobNumber > 0 || failedUsers > 0)
                ? DingTalkSyncLogService.STATUS_PARTIAL : DingTalkSyncLogService.STATUS_SUCCESS;
            if (summaries.isEmpty() && failedUsers > 0) {
                status = DingTalkSyncLogService.STATUS_FAILED;
            }
            syncLogService.finish(entity, status, users.size(), summaries.size(),
                noJobNumber, failedUsers, message);
            log.info("[钉钉考勤同步] {} 完成：{}", period, message);
        } catch (Exception e) {
            String message = "考勤同步失败：" + e.getMessage();
            syncLogService.finish(entity, DingTalkSyncLogService.STATUS_FAILED, 0, 0, 0, 0, message);
            log.error("[钉钉考勤同步] {} 失败：{}", period, e.getMessage(), e);
            throw new ServiceException(message);
        }
    }

    private YearMonth parseMonth(String period) {
        if (StringUtils.isBlank(period)) {
            throw new ServiceException("考勤期间不能为空（格式 YYYY-MM）");
        }
        try {
            return YearMonth.parse(period.trim());
        } catch (DateTimeParseException e) {
            throw new ServiceException("考勤期间格式不正确，应为 YYYY-MM：{}", period);
        }
    }

    /**
     * 单人数据：月度汇总 + 每日明细。
     */
    private UserRow fetchUser(String employeeCode, String userId, YearMonth month,
                              String leavePersonal, String leaveSick) {
        DingTalkAttendanceClient.UserMonthData data =
            attendanceClient.fetchMonth(userId, SUMMARY_COLUMNS, month);
        Map<String, BigDecimal> columns = data.columnSums();

        Map<String, BigDecimal> leaves = attendanceClient.leaveSums(userId, List.of(
            leavePersonal, leaveSick), month);
        BigDecimal personalDays = nz(leaves.get(leavePersonal));
        BigDecimal sickDays = nz(leaves.get(leaveSick));

        AttendanceSummarySyncDTO summary = new AttendanceSummarySyncDTO();
        summary.setEmployeeCode(employeeCode);
        summary.setAttendMonth(month.atDay(1));
        summary.setAttendDays(nz(columns.get(COL_ATTEND_DAYS)));
        summary.setRestDays(nz(columns.get(COL_REST_DAYS)));
        summary.setLateCount(intValue(columns.get(COL_LATE_TIMES)));
        summary.setLateMinutes(intValue(columns.get(COL_LATE_MINUTE)));
        summary.setMissingCardCount(intValue(columns.get(COL_LACK_ON_DUTY))
            + intValue(columns.get(COL_LACK_OFF_DUTY)));
        summary.setAbsentDays(nz(columns.get(COL_ABSENT_DAYS)));
        summary.setPersonalLeaveDays(personalDays);
        summary.setSickClinicDays(sickDays);
        summary.setSickHospitalDays(BigDecimal.ZERO);
        summary.setLeaveDays(personalDays.add(sickDays));

        List<AttendanceDetailSyncDTO> details = new ArrayList<>();
        for (Map.Entry<LocalDate, String> entry : data.dailyStatus().entrySet()) {
            AttendanceDetailSyncDTO detail = new AttendanceDetailSyncDTO();
            detail.setEmployeeCode(employeeCode);
            detail.setAttendDate(entry.getKey());
            detail.setStatus(entry.getValue());
            details.add(detail);
        }
        return new UserRow(summary, details);
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int intValue(BigDecimal value) {
        return value == null ? 0 : value.setScale(0, java.math.RoundingMode.HALF_UP).intValue();
    }

    /** 单人取数结果 */
    private record UserRow(AttendanceSummarySyncDTO summary, List<AttendanceDetailSyncDTO> details) {
    }
}
