package com.panjia.people.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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

    /** Warm-Flow 流程实例 ID */
    private String processInstanceId;

    // ==================== 异常考勤快照（提交时定格，总监只审异常行） ====================

    /** 该期间考勤总人数 */
    private Integer totalCount;

    /** 异常人数（迟到/迟到分/缺卡/旷工/请假 任一 >0） */
    private Integer abnormalCount;

    /** 异常行请假天数合计 */
    private BigDecimal abnormalLeaveDays;

    /** 异常考勤明细（总监审阅内容） */
    private List<AbnormalRow> abnormalRows;

    /** 异常考勤行 */
    @Data
    public static class AbnormalRow implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long employeeId;

        private String employeeCode;

        private String employeeName;

        /** 考勤月份（YYYY-MM-DD，当月 1 日） */
        private String attendMonth;

        private BigDecimal lateCount;

        private BigDecimal lateMinutes;

        private BigDecimal missingCardCount;

        private BigDecimal absentDays;

        private BigDecimal leaveDays;

        private String remark;
    }
}
