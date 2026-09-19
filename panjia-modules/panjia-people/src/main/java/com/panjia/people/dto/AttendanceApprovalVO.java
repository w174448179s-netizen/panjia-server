package com.panjia.people.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 考勤审批单 VO。
 */
@Data
public class AttendanceApprovalVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 归属期间（YYYY-MM） */
    private String period;

    /** 审批状态：DRAFT/SUBMITTED/APPROVED/REJECTED；无审批单为 null（未提交） */
    private String status;

    /** 提交人用户ID */
    private Long submitBy;

    /** 提交时间 */
    private LocalDateTime submitTime;

    /** 审批人用户ID */
    private Long approveBy;

    /** 审批时间 */
    private LocalDateTime approveTime;

    /** 驳回原因 */
    private String rejectReason;
}
