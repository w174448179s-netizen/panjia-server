package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 考勤审批单（一期间一行）。
 * <p>
 * 人事提交当月考勤 → 总监审批；通过后该期间方可创建薪酬批次（进入算薪）。
 * 考勤重新导入时 SUBMITTED/APPROVED 单据失效回 DRAFT（见 ApprovalService.invalidate）。
 */
@Data
@TableName("pj_people_attendance_approval")
public class AttendanceApproval implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 审批状态：待提交 */
    public static final String STATUS_DRAFT = "DRAFT";

    /** 审批状态：待总监审批 */
    public static final String STATUS_SUBMITTED = "SUBMITTED";

    /** 审批状态：总监已通过 */
    public static final String STATUS_APPROVED = "APPROVED";

    /** 审批状态：已驳回 */
    public static final String STATUS_REJECTED = "REJECTED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 归属期间（YYYY-MM） */
    private String period;

    /** 审批状态 */
    private String status;

    /** 提交人（人事）用户ID */
    private Long submitBy;

    /** 提交时间 */
    private LocalDateTime submitTime;

    /** 审批人（总监）用户ID */
    private Long approveBy;

    /** 审批时间 */
    private LocalDateTime approveTime;

    /** 驳回原因 */
    private String rejectReason;

    /** Warm-Flow 流程实例 ID（attendance_approval 流程） */
    private String processInstanceId;

    /** 提交时异常考勤快照 JSON（迟到/迟到分/缺卡/旷工/请假 >0 的行） */
    private String snapshot;

    @Version
    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
