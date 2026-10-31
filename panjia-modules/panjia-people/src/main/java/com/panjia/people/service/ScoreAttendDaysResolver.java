package com.panjia.people.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.people.domain.AttendanceDetail;
import com.panjia.people.domain.AttendanceRecord;
import com.panjia.people.mapper.AttendanceDetailMapper;
import com.panjia.people.mapper.AttendanceRecordMapper;
import com.panjia.people.port.ConfigPort;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 平均积分的分母：考勤出勤天数。
 * <p>
 * 原口径用「有积分日报的 DISTINCT 填报日期数」，员工可在当日积分低时干脆不填报，
 * 从而抬高平均积分并规避等级扣点。现统一改为取考勤汇总
 * （{@code pj_people_attendance.attend_days}，人事/总监维护的权威出勤事实）。
 * <p>
 * 钉钉报表的「出勤天数」含已批准的请假（应出勤口径），会拉低在休长假员工的平均积分。
 * 因此按考勤每日明细（{@code pj_people_attendance_detail.status} 原文）中的假期名称，
 * 扣除不参与积分考核的假期天数（名单见参数管理 {@link #CONFIG_KEY}）；
 * 休息/未排班日即使挂假也不扣，避免重复扣减非应出勤日。
 * <p>
 * 考勤数据缺失（未导入/未登记）时回退积分表存储的填报天数，保证历史期间结果不变。
 */
@Component
@RequiredArgsConstructor
public class ScoreAttendDaysResolver {

    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 参数管理键名：平均积分分母需扣除的假期名单（逗号分隔） */
    private static final String CONFIG_KEY = "panjia.score.deduct_leave_names";

    /** 参数未配置时的兜底名单（与初始化脚本默认值一致） */
    private static final List<String> DEFAULT_DEDUCT_LEAVE_NAMES =
        List.of("随心假", "年假", "生日假", "产假", "陪产假", "丧假", "病假（二甲医院以上）");

    /** 非应出勤日标记：这些日期即使出现假期名称也不扣减出勤天数 */
    private static final Set<String> NON_WORK_MARKERS = Set.of("休息", "未排班");

    private final AttendanceRecordMapper attendanceRecordMapper;
    private final AttendanceDetailMapper attendanceDetailMapper;
    private final ConfigPort configPort;

    /**
     * 批量解析考勤出勤天数（已扣除不参与考核的假期天数）。
     *
     * @param employeeIds 员工 ID 集合
     * @param monthStarts 涉及的月份（每月 1 日）
     * @return key = {@code employeeId|yyyy-MM} → 考勤出勤天数（仅有有效值的才放入）
     */
    public Map<String, Integer> resolve(Collection<Long> employeeIds, Collection<LocalDate> monthStarts) {
        if (employeeIds == null || employeeIds.isEmpty() || monthStarts == null || monthStarts.isEmpty()) {
            return Map.of();
        }
        List<AttendanceRecord> rows = attendanceRecordMapper.selectList(new LambdaQueryWrapper<AttendanceRecord>()
            .in(AttendanceRecord::getEmployeeId, employeeIds)
            .in(AttendanceRecord::getAttendMonth, monthStarts)
            .select(AttendanceRecord::getEmployeeId, AttendanceRecord::getAttendMonth,
                AttendanceRecord::getAttendDays));
        Map<String, Integer> deductDays = resolveDeductLeaveDays(employeeIds, monthStarts);
        Map<String, Integer> result = new HashMap<>();
        for (AttendanceRecord row : rows) {
            Integer days = toInt(row.getAttendDays());
            if (row.getEmployeeId() == null || row.getAttendMonth() == null || days == null || days <= 0) {
                continue;
            }
            String key = key(row.getEmployeeId(), row.getAttendMonth());
            int effectiveDays = Math.max(days - deductDays.getOrDefault(key, 0), 0);
            result.put(key, effectiveDays);
        }
        return result;
    }

    /** 解析键：employeeId|yyyy-MM */
    public static String key(Long employeeId, LocalDate monthStart) {
        return employeeId + "|" + (monthStart == null ? "" : monthStart.format(MONTH_KEY));
    }

    /**
     * 按考勤每日明细统计需扣除的假期天数（同人同月同一天只算一次）。
     */
    private Map<String, Integer> resolveDeductLeaveDays(Collection<Long> employeeIds,
                                                        Collection<LocalDate> monthStarts) {
        List<String> deductLeaveNames = loadDeductLeaveNames();
        List<AttendanceDetail> details = attendanceDetailMapper.selectList(new LambdaQueryWrapper<AttendanceDetail>()
            .in(AttendanceDetail::getEmployeeId, employeeIds)
            .in(AttendanceDetail::getAttendMonth, monthStarts)
            .select(AttendanceDetail::getEmployeeId, AttendanceDetail::getAttendMonth,
                AttendanceDetail::getAttendDate, AttendanceDetail::getStatus));
        Map<String, Set<LocalDate>> datesByEmployeeMonth = new HashMap<>();
        for (AttendanceDetail detail : details) {
            if (detail.getEmployeeId() == null || detail.getAttendMonth() == null
                || detail.getAttendDate() == null || !isDeductLeave(detail.getStatus(), deductLeaveNames)) {
                continue;
            }
            datesByEmployeeMonth
                .computeIfAbsent(key(detail.getEmployeeId(), detail.getAttendMonth()), k -> new HashSet<>())
                .add(detail.getAttendDate());
        }
        Map<String, Integer> result = new HashMap<>();
        datesByEmployeeMonth.forEach((key, dates) -> result.put(key, dates.size()));
        return result;
    }

    /**
     * 从参数管理读取需扣除的假期名单（逗号分隔，兼容中英文逗号）；未配置时用兜底名单。
     */
    private List<String> loadDeductLeaveNames() {
        String value = configPort.findValue(CONFIG_KEY);
        if (StringUtils.isBlank(value)) {
            return DEFAULT_DEDUCT_LEAVE_NAMES;
        }
        List<String> names = new ArrayList<>();
        for (String part : value.split("[,，]")) {
            if (StringUtils.isNotBlank(part)) {
                names.add(part.trim());
            }
        }
        return names.isEmpty() ? DEFAULT_DEDUCT_LEAVE_NAMES : names;
    }

    /** 状态原文是否属于需扣除的假期（休息/未排班日不计） */
    private boolean isDeductLeave(String status, List<String> deductLeaveNames) {
        if (StringUtils.isBlank(status)) {
            return false;
        }
        String text = normalize(status);
        for (String marker : NON_WORK_MARKERS) {
            if (text.contains(marker)) {
                return false;
            }
        }
        for (String name : deductLeaveNames) {
            String keyword = normalize(name);
            if (StringUtils.isNotBlank(keyword) && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 归一化：全角/半角括号统一，去掉空白，避免假期名称因格式差异匹配失败 */
    private String normalize(String text) {
        return text.replace('(', '（').replace(')', '）').replaceAll("\\s+", "");
    }

    private static Integer toInt(BigDecimal value) {
        return value == null ? null : value.setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
