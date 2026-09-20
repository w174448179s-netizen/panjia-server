package com.panjia.contracts.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 考勤月度指标（按员工聚合，供薪酬域考勤扣款计算）。
 * <p>
 * 来源：员工域 {@code pj_people_attendance}（取数端口见 {@code PeopleAttendanceMetricsQueryPort}），
 * 引擎按 {@code policy.attendance} 配置（lateFee / workDaysPerMonth / absentWithBaseTimes /
 * absentNoBaseFee / leaveFee）从下面这些原始字段计算扣款——不预先按规则算好。
 * <p>
 * 旧版兼容：
 * <ul>
 *   <li>{@code importedFee} — 旧扁平模板「扣款金额」列导过来的固定金额；员工域恒 0，
 *   月度业绩指标导入的其它扣减（如「7.1-121.31日何方方提成扣2%」）由
 *   {@code MonthlyMetricPort} 单独提供。</li>
 *   <li>{@code lateCount}/{@code absentDays}/{@code leaveDays} — 钉钉月度汇总/员工域手工维护的原始数据。</li>
 * </ul>
 */
@Data
public class AttendanceMetricsDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 导入扣款金额（旧扁平模板「扣款金额」列；钉钉月度模板无此列，员工域恒为 0） */
    private BigDecimal importedFee;

    /** 迟到次数（月合计） */
    private Integer lateCount;

    /** 旷工天数（月合计） */
    private BigDecimal absentDays;

    /** 请假天数（事假+病假合计，月合计） */
    private BigDecimal leaveDays;
}
