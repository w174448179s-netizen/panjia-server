package com.panjia.people.service;

import com.panjia.contracts.dto.PointsRuleDTO;
import com.panjia.contracts.port.PointsRuleQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 积分派生字段计算器（实时计算，不落库）。
 * <p>
 * 平均积分 / 绩效等级 / 提成扣点 / 积分扣款 均由原始事实
 * （总积分、出勤天数、晚提交次数）查询时推导。计算口径来自
 * 薪酬政策规则表（pj_payroll_policy_rule GLOBAL 的 rule_content.points）。
 * <p>
 * 使用方式：调用方先经 {@link #currentRule()} 查一次规则，
 * 再把 {@link PointsRuleDTO} 显式传入各计算方法——规则在一次批量计算
 * （整页列表/审批快照/算薪全员）内是常量，禁止逐行重复查询。
 * 规则为 null（无 GLOBAL 规则/解析失败/规则源异常）时各方法兜底默认口径
 * （A≥8 不扣；6~8 B 扣 2%；&lt;6 C 扣 4%；晚提交 5 元/次）。
 */
@Component
@RequiredArgsConstructor
public class ScoreGradePolicy {

    // ==================== 兜底默认口径（规则缺失时使用） ====================

    private static final BigDecimal DEFAULT_GRADE_A_MIN = new BigDecimal("8");
    private static final BigDecimal DEFAULT_GRADE_B_MIN = new BigDecimal("6");
    private static final BigDecimal DEFAULT_DEDUCT_A = BigDecimal.ZERO;
    private static final BigDecimal DEFAULT_DEDUCT_B = new BigDecimal("-0.02");
    private static final BigDecimal DEFAULT_DEDUCT_C = new BigDecimal("-0.04");
    private static final BigDecimal DEFAULT_LATE_FEE = new BigDecimal("5");

    private final PointsRuleQueryPort pointsRuleQueryPort;

    /**
     * 查询当前积分规则。一次批量计算开头调用一次，结果作为参数传入各计算方法；
     * 不得在逐行循环内调用。无规则/规则源异常返回 null（计算方法自动兜底默认口径）。
     */
    public PointsRuleDTO currentRule() {
        try {
            return pointsRuleQueryPort.pointsRule();
        } catch (Exception e) {
            return null;
        }
    }

    /** 平均积分 = 总积分 / 出勤天数（出勤 0 天或总积分为空返回 null），不依赖规则 */
    public BigDecimal avgPoints(BigDecimal totalPoints, Integer attendDays) {
        if (totalPoints == null || attendDays == null || attendDays <= 0) {
            return null;
        }
        return totalPoints.divide(BigDecimal.valueOf(attendDays), 4, RoundingMode.HALF_UP);
    }

    /** 绩效等级：平均分 ≥A门槛 → A；≥B门槛 → B；否则 C（出勤 0 天为 null，算薪默认 A 不扣点） */
    public String grade(PointsRuleDTO rule, BigDecimal totalPoints, Integer attendDays) {
        BigDecimal avg = avgPoints(totalPoints, attendDays);
        return avg == null ? null : resolveGrade(rule, avg);
    }

    /** 平均分 → 等级：≥A门槛 → A；≥B门槛 → B；否则 C */
    public String resolveGrade(PointsRuleDTO rule, BigDecimal avgPoints) {
        BigDecimal aMin = rule != null && rule.getGradeAMin() != null ? rule.getGradeAMin() : DEFAULT_GRADE_A_MIN;
        BigDecimal bMin = rule != null && rule.getGradeBMin() != null ? rule.getGradeBMin() : DEFAULT_GRADE_B_MIN;
        if (avgPoints.compareTo(aMin) >= 0) {
            return "A";
        }
        return avgPoints.compareTo(bMin) >= 0 ? "B" : "C";
    }

    /** 提成扣点小数：等级对应 deduct 值（A=deductA B=deductB C=deductC）；等级为空返回 null */
    public BigDecimal deductOf(PointsRuleDTO rule, String grade) {
        return switch (grade == null ? "" : grade) {
            case "A" -> rule != null && rule.getDeductA() != null ? rule.getDeductA() : DEFAULT_DEDUCT_A;
            case "B" -> rule != null && rule.getDeductB() != null ? rule.getDeductB() : DEFAULT_DEDUCT_B;
            case "C" -> rule != null && rule.getDeductC() != null ? rule.getDeductC() : DEFAULT_DEDUCT_C;
            default -> null;
        };
    }

    /** 积分扣款 = 晚提交次数 × 罚款单价（政策规则 penaltyFee；免罚由人事调整晚提交次数实现） */
    public BigDecimal lateFeeOf(PointsRuleDTO rule, Integer lateSubmitCount) {
        BigDecimal unit = rule != null && rule.getLateFeePerTime() != null
            ? rule.getLateFeePerTime()
            : DEFAULT_LATE_FEE;
        int count = lateSubmitCount == null ? 0 : lateSubmitCount;
        return unit.multiply(BigDecimal.valueOf(count)).setScale(2, RoundingMode.HALF_UP);
    }
}
