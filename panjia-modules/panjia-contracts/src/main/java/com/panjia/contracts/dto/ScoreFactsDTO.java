package com.panjia.contracts.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 员工月度绩效积分事实（薪酬域按员工聚合）。
 * <p>
 * 端口只装原始事实（与 {@link AttendanceMetricsDTO} 同模式）：
 * <ul>
 *   <li>{@code totalPoints} — 当月总积分（来自 {@code pj_performance_score}）</li>
 *   <li>{@code attendDays} — 当月出勤天数（来自 {@code pj_performance_score}）</li>
 *   <li>{@code lateSubmitCount} — 当月晚提交次数（来自 {@code pj_performance_score}）</li>
 * </ul>
 * 引擎（{@code SalaryCalculationEngine}）按 {@code policy.points} 配置
 * （gradeA / gradeB / deductA / deductB / deductC / penaltyFee）从上面这些事实
 * 推导绩效等级（A/B/C）与积分扣款（晚提交次数 × penaltyFee），不再由端口预先按规则算好。
 * <p>
 * 该 DTO 与 {@code AttendanceMetricsDTO} 同属「事实聚合」层：薪资域不直接访问
 * 员工域规则（{@code PointsRuleQueryPort}），通过 {@code snapshot.policy()} 拉规则，
 * 保证算薪上下文自带规则版本。
 */
@Data
public class ScoreFactsDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 当月总积分（事实） */
    private BigDecimal totalPoints;

    /** 当月出勤天数（事实） */
    private Integer attendDays;

    /** 当月晚提交次数（事实） */
    private Integer lateSubmitCount;
}
