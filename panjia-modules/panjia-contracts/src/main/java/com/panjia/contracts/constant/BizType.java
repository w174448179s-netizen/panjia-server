package com.panjia.contracts.constant;

/**
 * 审批业务类型常量。业务域经 {@link com.panjia.contracts.port.ApprovalPort} 调用审批能力时，
 * 以本常量标识业务单据类型；适配器据此映射到具体流程定义编码（flowCode）。
 * <p>
 * 换引擎时只需改适配器内的 bizType → flowCode 映射，业务域无感。
 */
public final class BizType {

    /** 实收业绩确认 */
    public static final String REAL_CONFIRM = "REAL_CONFIRM";
    /** 结佣申请 */
    public static final String COMMISSION = "COMMISSION";
    /** 业绩调整 */
    public static final String PERF_ADJUST = "PERF_ADJUST";
    /** 实收调整（pj_perf_received_adjust，2026-10-12 与新签调整物理拆表） */
    public static final String PERF_RECEIVED_ADJUST = "PERF_RECEIVED_ADJUST";
    /** 薪酬批次 */
    public static final String PAYROLL_BATCH = "PAYROLL_BATCH";
    /** 职级变动通知（算薪自动晋升/降级，财务/总监知会） */
    public static final String PAYROLL_PROMOTION = "PAYROLL_PROMOTION";
    /** 考勤月度审批 */
    public static final String ATTENDANCE_APPROVAL = "ATTENDANCE_APPROVAL";
    /** 积分月度审批 */
    public static final String SCORE_APPROVAL = "SCORE_APPROVAL";
    /** 提成点调整（员工业绩扣点，总监审批） */
    public static final String RATE_ADJUST = "RATE_ADJUST";
    /** 考勤修改申请（经纪人发起，人事审批） */
    public static final String ATTENDANCE_RECTIFY = "ATTENDANCE_RECTIFY";

    /** 退单申请（合同退单，负新签 + 负实收联动，2026-10-23） */
    public static final String PERF_REFUND = "PERF_REFUND";

    private BizType() {
    }
}
