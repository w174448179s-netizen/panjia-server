package com.panjia.people.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 积分审批单 VO。
 */
@Data
public class ScoreApprovalVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 归属期间（YYYY-MM） */
    private String period;

    /** 审批状态：DRAFT/SUBMITTED/APPROVED/REJECTED；无审批单为 null（未提交） */
    private String status;

    /** 该月是否有积分数据（false = 无积分，绩效等级全默认 A 不扣点，创建批次前需人工确认） */
    private Boolean dataExists;

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

    // ==================== 扣点行快照（提交时定格，总监只审 B/C 级行） ====================

    /** 该期间积分总人数 */
    private Integer totalCount;

    /** A 级人数（不扣点，免审） */
    private Integer gradeACount;

    /** B 级人数（扣点 -2%） */
    private Integer gradeBCount;

    /** C 级人数（扣点 -4%） */
    private Integer gradeCCount;

    /** 扣点行明细（B/C 级，总监审阅内容） */
    private List<DeductRow> deductRows;

    /** 扣点行（绩效等级 B/C） */
    @Data
    public static class DeductRow implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long employeeId;

        private String employeeCode;

        private String employeeName;

        /** 积分月份（YYYY-MM-DD，当月 1 日） */
        private String scoreMonth;

        /** 总积分 */
        private BigDecimal totalPoints;

        /** 出勤天数 */
        private Integer attendDays;

        /** 平均积分 */
        private BigDecimal avgPoints;

        /** 绩效等级 B/C */
        private String grade;

        /** 提成扣点小数 */
        private BigDecimal deductRate;
    }
}
