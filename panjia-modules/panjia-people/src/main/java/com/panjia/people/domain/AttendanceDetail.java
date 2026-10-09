package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 考勤每日明细（一员工一天一行）。
 * <p>
 * 来源：钉钉月度汇总导入同步（AttendanceArchiveHandler 消费归档事件时，
 * AttendanceService.syncAttendanceDetails 先删后插写入），从 Excel Q+ 列解析。
 */
@Data
@TableName("pj_people_attendance_detail")
public class AttendanceDetail implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long employeeId;

    /** 考勤月份（当月 1 日，冗余便于按月清理） */
    private LocalDate attendMonth;

    /** 考勤日期 */
    private LocalDate attendDate;

    /** 考勤状态原文（正常/迟到/缺卡/旷工/请假/休息） */
    private String status;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
