package com.panjia.contracts.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 考勤月度指标（按员工聚合，供薪酬域考勤扣款计算）。
 * <p>
 * 来源：员工域 {@code pj_people_attendance}（取数端口见 {@code PeopleAttendanceMetricsQueryPort}），
 * 引擎按 {@code policy.attendance} 配置（lateFee / personalLeaveFee /
 * sickClinicWithBaseFee / sickClinicNoBaseFee / sickHospitalWithBaseFee /
 * sickHospitalNoBaseFee / absentWithBaseTimes / absentNoBaseFee）从下面这些原始字段
 * 计算扣款——不预先按规则算好。
 * <p>
 * 请假天数按业务拆为三类（2026-10 钉钉《月度汇总》V210 起病假拆分）：
 * 事假 / 病假（诊所类证明）/ 病假（二甲医院以上）；{@code leaveDays} 为三类合计
 * （旧版兼容/展示口径），旧数据仅有合计时引擎按 leaveFee 兜底扣款。
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

    /** 事假天数（月合计） */
    private BigDecimal personalLeaveDays;

    /** 病假天数-诊所类证明（月合计，V210 模板拆分） */
    private BigDecimal sickClinicDays;

    /** 病假天数-二甲医院以上（月合计，V210 模板拆分） */
    private BigDecimal sickHospitalDays;

    /** 请假天数（事假+病假三类合计，旧版兼容/展示口径；仅旧合计数据按 leaveFee 兜底） */
    private BigDecimal leaveDays;
}
