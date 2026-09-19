package com.panjia.contracts.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 考勤月度汇总同步单条（导入域 → 员工域）。
 * <p>
 * 来源：钉钉《月度汇总》导入批次归档后的原始考勤行（一行一人一月），
 * 由员工域按工号匹配员工档案后 upsert 进 pj_people_attendance。
 */
@Data
public class AttendanceSummarySyncDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 工号（pj_people_employee.employee_code） */
    private String employeeCode;

    /** 考勤月份（当月 1 日） */
    private LocalDate attendMonth;

    /** 出勤天数 */
    private BigDecimal attendDays;

    /** 休息天数 */
    private BigDecimal restDays;

    /** 迟到次数 */
    private Integer lateCount;

    /** 迟到时长（分钟） */
    private Integer lateMinutes;

    /** 缺卡次数 */
    private Integer missingCardCount;

    /** 旷工天数 */
    private BigDecimal absentDays;

    /** 请假天数（事假+病假合计） */
    private BigDecimal leaveDays;
}
