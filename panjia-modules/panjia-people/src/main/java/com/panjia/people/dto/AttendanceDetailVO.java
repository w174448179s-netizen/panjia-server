package com.panjia.people.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 考勤每日明细 VO。
 */
@Data
public class AttendanceDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long employeeId;
    private String employeeCode;
    private String employeeName;

    /** 考勤日期 */
    private LocalDate attendDate;

    /** 考勤状态原文 */
    private String status;
}
