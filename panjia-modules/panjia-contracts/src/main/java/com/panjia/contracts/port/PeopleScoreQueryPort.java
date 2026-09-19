package com.panjia.contracts.port;

import java.util.Map;

/**
 * 积分数据跨域查询端口（payroll → people）。
 * <p>
 * 算薪时按期间取员工绩效等级（A/B/C，来自积分表按「出勤日平均积分」判定），
 * 引擎按 policy.points.deduct{grade} 计算绩效扣点。
 */
public interface PeopleScoreQueryPort {

    /**
     * 查询期间各员工绩效等级。
     *
     * @param period 期间 YYYY-MM
     * @return employeeId → 等级（A/B/C）；无积分数据的员工不在 Map 中（引擎默认 A）
     */
    Map<Long, String> scoreGrades(String period);
}
