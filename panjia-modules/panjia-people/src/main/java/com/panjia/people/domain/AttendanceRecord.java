package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 月考勤汇总（对应 pj_people_attendance 表，一员工一月一行）。
 * <p>
 * 粒度对齐钉钉《月度汇总》导入数据，服务薪酬扣款（请假/旷工）。
 * 人事/总监可手工维护，员工本人仅可查询自己的记录。
 * 数据来源 dataSource：MANUAL=人工登记，预留未来 DINGTALK=钉钉同步。
 * <p>
 * 不继承 RuoYi BaseEntity：本表无 create_by/update_by 审计列，
 * create_time/update_time 由数据库默认值填充（同 {@link Employee}）。
 */
@Data
@TableName("pj_people_attendance")
public class AttendanceRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 记录 ID，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 员工 ID */
    private Long employeeId;

    /** 考勤月份（当月 1 日） */
    private LocalDate attendMonth;

    /** 请假天数（事假+病假合计，月合计） */
    private BigDecimal leaveDays;

    /** 旷工天数（月合计） */
    private BigDecimal absentDays;

    /** 迟到次数（月合计） */
    private Integer lateCount;

    /** 迟到时长（分钟，月合计） */
    private Integer lateMinutes;

    /** 缺卡次数（月合计） */
    private Integer missingCardCount;

    /** 出勤天数 */
    private BigDecimal attendDays;

    /** 休息天数 */
    private BigDecimal restDays;

    /** 数据来源：MANUAL=人工登记；预留 DINGTALK=钉钉同步 */
    private String dataSource;

    /** 备注（可空，编辑清空时需写回 NULL，故 update 始终参与） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String remark;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;

    /** 更新时间（DB 默认填充） */
    private LocalDateTime updateTime;
}
