package com.panjia.people.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.people.domain.AttendanceRecord;
import com.panjia.people.mapper.AttendanceRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 平均积分的分母：考勤出勤天数。
 * <p>
 * 原口径用「有积分日报的 DISTINCT 填报日期数」，员工可在当日积分低时干脆不填报，
 * 从而抬高平均积分并规避等级扣点。现统一改为取考勤汇总
 * （{@code pj_people_attendance.attend_days}，人事/总监维护的权威出勤事实）。
 * <p>
 * 考勤数据缺失（未导入/未登记）时回退积分表存储的填报天数，保证历史期间结果不变。
 */
@Component
@RequiredArgsConstructor
public class ScoreAttendDaysResolver {

    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");

    private final AttendanceRecordMapper attendanceRecordMapper;

    /**
     * 批量解析考勤出勤天数。
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
        Map<String, Integer> result = new HashMap<>();
        for (AttendanceRecord row : rows) {
            Integer days = toInt(row.getAttendDays());
            if (row.getEmployeeId() != null && row.getAttendMonth() != null && days != null && days > 0) {
                result.put(key(row.getEmployeeId(), row.getAttendMonth()), days);
            }
        }
        return result;
    }

    /** 解析键：employeeId|yyyy-MM */
    public static String key(Long employeeId, LocalDate monthStart) {
        return employeeId + "|" + (monthStart == null ? "" : monthStart.format(MONTH_KEY));
    }

    private static Integer toInt(BigDecimal value) {
        return value == null ? null : value.setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
