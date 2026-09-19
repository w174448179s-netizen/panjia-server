package com.panjia.people.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 考勤汇总视图（人月维度，记录字段 + 员工/部门展示冗余）。
 */
@Data
public class AttendanceVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 记录 ID */
    private Long id;

    /** 员工 ID */
    private Long employeeId;

    /** 考勤月份（当月 1 日） */
    private LocalDate attendMonth;

    /** 请假天数（事假+病假合计） */
    private BigDecimal leaveDays;

    /** 旷工天数 */
    private BigDecimal absentDays;

    /** 迟到次数 */
    private Integer lateCount;

    /** 迟到时长（分钟） */
    private Integer lateMinutes;

    /** 缺卡次数 */
    private Integer missingCardCount;

    /** 出勤天数 */
    private BigDecimal attendDays;

    /** 休息天数 */
    private BigDecimal restDays;

    /** 数据来源：MANUAL/DINGTALK */
    private String dataSource;

    /** 备注 */
    private String remark;

    /** 乐观锁版本号 */
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    // ==================== 展示冗余 ====================

    /** 工号 */
    private String employeeCode;

    /** 姓名 */
    private String employeeName;

    /** 部门 ID */
    private Long deptId;

    /** 部门全路径名 */
    private String deptName;
}
