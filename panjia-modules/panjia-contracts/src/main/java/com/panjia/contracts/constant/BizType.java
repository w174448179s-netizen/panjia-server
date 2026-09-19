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
    /** 结佣调整 */
    public static final String COMMISSION_ADJUST = "COMMISSION_ADJUST";
    /** 薪酬批次 */
    public static final String PAYROLL_BATCH = "PAYROLL_BATCH";
    /** 考勤月度审批 */
    public static final String ATTENDANCE_APPROVAL = "ATTENDANCE_APPROVAL";
    /** 积分月度审批 */
    public static final String SCORE_APPROVAL = "SCORE_APPROVAL";
    /** 提成点调整（员工业绩扣点，总监审批） */
    public static final String RATE_ADJUST = "RATE_ADJUST";

    private BizType() {
    }
}
