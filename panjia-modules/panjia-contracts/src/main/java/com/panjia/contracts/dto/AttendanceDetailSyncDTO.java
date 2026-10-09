package com.panjia.contracts.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 考勤每日明细同步单条（导入域 → 员工域）。
 * <p>
 * 来源：钉钉《月度汇总》Excel Q+ 列的每日考勤结果，
 * 由导入域 AttendanceSummaryAggregator 聚合时附带每日明细，
 * 员工域 AttendanceArchiveHandler 消费后写入 pj_people_attendance_detail。
 */
@Data
public class AttendanceDetailSyncDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 工号（pj_people_employee.employee_code） */
    private String employeeCode;

    /** 考勤日期 */
    private LocalDate attendDate;

    /** 考勤状态原文（正常/迟到/缺卡/旷工/请假/休息） */
    private String status;
}
