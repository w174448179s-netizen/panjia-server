package com.panjia.people.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 考勤汇总新增/编辑请求（人月维度，一员工一月一行）。
 * <p>
 * 请假/旷工天数为月合计，可大于 1；迟到次数/时长、缺卡次数为月汇总。
 */
@Data
public class AttendanceSaveDTO {

    /** 员工 ID（必填） */
    @NotNull(message = "员工不能为空")
    private Long employeeId;

    /** 考勤月份（必填，当月 1 日） */
    @NotNull(message = "考勤月份不能为空")
    private LocalDate attendMonth;

    /** 请假天数（事假+病假合计，非负） */
    @DecimalMin(value = "0", message = "请假天数不能小于 0")
    private BigDecimal leaveDays;

    /** 旷工天数（非负） */
    @DecimalMin(value = "0", message = "旷工天数不能小于 0")
    private BigDecimal absentDays;

    /** 迟到次数（非负整数） */
    @PositiveOrZero(message = "迟到次数不能为负")
    private Integer lateCount;

    /** 迟到时长（分钟，非负整数） */
    @PositiveOrZero(message = "迟到时长不能为负")
    private Integer lateMinutes;

    /** 缺卡次数（非负整数） */
    @PositiveOrZero(message = "缺卡次数不能为负")
    private Integer missingCardCount;

    /** 出勤天数 */
    @DecimalMin(value = "0", message = "出勤天数不能小于 0")
    private BigDecimal attendDays;

    /** 休息天数 */
    @DecimalMin(value = "0", message = "休息天数不能小于 0")
    private BigDecimal restDays;

    /** 备注 */
    @Size(max = 512, message = "备注长度不能超过 512 个字符")
    private String remark;

    /** 乐观锁版本号（编辑时回传，用于并发覆盖检测；新增不传） */
    private Integer version;
}
