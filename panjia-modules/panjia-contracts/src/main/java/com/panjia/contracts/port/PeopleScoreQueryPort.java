package com.panjia.contracts.port;

import com.panjia.contracts.dto.ScoreFactsDTO;

import java.util.Map;

/**
 * 积分事实跨域查询端口（payroll → people）。
 * <p>
 * 端口只装原始事实（{@link ScoreFactsDTO}），不预先按规则算绩效等级/扣款金额——
 * 等级（A/B/C）与积分扣款（晚提交次数 × penaltyFee）由
 * {@code SalaryCalculationEngine} 按 {@code policy.points} 规则计算，
 * 与 {@code PeopleAttendanceMetricsQueryPort} → {@code AttendanceMetricsDTO}
 * 「事实聚合」层模式一致。
 *
 * @author panjia
 * @since 2026-09
 */
public interface PeopleScoreQueryPort {

    /**
     * 查询期间各员工积分事实（聚合：总分、出勤天数、晚提交次数）。
     *
     * @param period 期间 YYYY-MM
     * @return employeeId → 积分事实；无积分数据的员工不在 Map 中
     *         （引擎侧按 strategy.A 不扣点处理，见 {@code SalaryCalculationEngine}）
     */
    Map<Long, ScoreFactsDTO> scoreFacts(String period);
}
