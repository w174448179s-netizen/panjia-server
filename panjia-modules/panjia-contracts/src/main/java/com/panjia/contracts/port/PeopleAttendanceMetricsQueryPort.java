package com.panjia.contracts.port;

import com.panjia.contracts.dto.AttendanceMetricsDTO;

import java.util.Map;

/**
 * 员工月度考勤指标查询端口（薪酬域 → 员工域）。
 * <p>
 * 数据源：{@code pj_people_attendance}（员工域考勤明细维护的权威源，
 * 人事/总监在「考勤明细」页登记/调整，员工域内部通过 {@code PeopleAttendanceSyncPort}
 * 由导入归档后 upsert）。<strong>不要</strong>从导入归一表（{@code pj_normalized_record}）
 * 取——人事/总监的手工调整不写回归一表，会导致算薪与考勤明细页数据不一致。
 * <p>
 * 注意 {@link AttendanceMetricsDTO#importedFee}：考勤事实不包含「导入扣款金额」，
 * 该字段始终为 {@code 0}。一次性扣款（如「7.1-121.31日何方方提成扣2%」）由
 * 月度业绩指标导入（{@code MonthlyMetricPort}）提供，不属于考勤。
 *
 * @author panjia
 * @since 2026-09
 */
public interface PeopleAttendanceMetricsQueryPort {

    /**
     * 按 period（YYYY-MM）汇总当月所有员工的考勤指标。
     *
     * @param period 期间字符串，YYYY-MM
     * @return employeeId → 考勤指标（按员工的原始事实 DTO）；空集合代表当月无考勤数据
     */
    Map<Long, AttendanceMetricsDTO> sumByPeriod(String period);
}
