package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 考勤修改申请单（经纪人按月发起，一人一单一在途）。
 * <p>
 * 经纪人对自己某月考勤的月汇总指标发起调整（changes_json 记录 原值→新值），
 * 附截图（oss_ids）与说明，提交人事审批（Warm-Flow attendance_rectify）。
 * 审批通过后回写 pj_people_attendance 并使当月考勤月度审批失效回退，
 * 人事再统一提交总监审批；驳回即终态，经纪人可重新发起新单。
 * <p>
 * 不继承 RuoYi BaseEntity：本表无 create_by/update_by 审计列（同 {@link AttendanceRecord}）。
 */
@Data
@TableName("pj_people_attendance_rectify")
public class AttendanceRectify implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 审批状态：待人事审批 */
    public static final String STATUS_SUBMITTED = "SUBMITTED";

    /** 审批状态：已通过（已回写月考勤） */
    public static final String STATUS_APPROVED = "APPROVED";

    /** 审批状态：已驳回（终态） */
    public static final String STATUS_REJECTED = "REJECTED";

    /** 审批状态：已取消 */
    public static final String STATUS_CANCELED = "CANCELED";

    /** 记录 ID，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 申请人员工 ID */
    private Long employeeId;

    /** 考勤月份（当月 1 日） */
    private LocalDate attendMonth;

    /** 修改明细 JSON：[{field,fieldName,oldValue,newValue}]，field 限定月汇总指标白名单 */
    private String changesJson;

    /** 涉及日期（逗号分隔 yyyy-MM-dd，说明性，可空） */
    private String involveDates;

    /** 修改说明 */
    private String reason;

    /** 截图 ossId 列表（逗号分隔，≥1 张） */
    private String ossIds;

    /** 审批状态 */
    private String status;

    /** Warm-Flow 流程实例 ID（attendance_rectify 流程） */
    private String processInstanceId;

    /** 提交人（经纪人）用户ID */
    private Long submitBy;

    /** 提交时间 */
    private LocalDateTime submitTime;

    /** 审批人（人事）用户ID */
    private Long approveBy;

    /** 审批时间 */
    private LocalDateTime approveTime;

    /** 驳回原因 */
    private String rejectReason;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;

    /** 更新时间（DB 默认填充） */
    private LocalDateTime updateTime;
}
