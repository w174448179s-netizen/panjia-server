package com.panjia.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 积分规则（薪酬政策规则 rule_content.points 的对外视图）。
 * <p>
 * 积分派生字段（平均积分/绩效等级/提成扣点/积分扣款）的计算口径全部来源于此，
 * 由薪酬域政策规则表（pj_payroll_policy_rule，GLOBAL）统一配置。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PointsRuleDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 等级 A 门槛：平均积分 ≥ gradeAMin → A */
    private BigDecimal gradeAMin;

    /** 等级 B 门槛：平均积分 ≥ gradeBMin → B，否则 C */
    private BigDecimal gradeBMin;

    /** A 级提成扣点（小数，0 表示不扣） */
    private BigDecimal deductA;

    /** B 级提成扣点（小数，负数表示扣减，如 -0.02） */
    private BigDecimal deductB;

    /** C 级提成扣点（小数，负数表示扣减，如 -0.04） */
    private BigDecimal deductC;

    /** 晚提交罚款单价（元/次） */
    private BigDecimal lateFeePerTime;
}
