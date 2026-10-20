package com.panjia.payroll.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.dto.PointsRuleDTO;
import com.panjia.contracts.port.PointsRuleQueryPort;
import com.panjia.payroll.domain.PerfScheme;
import com.panjia.payroll.domain.PolicyRule;
import com.panjia.payroll.mapper.PerfSchemeMapper;
import com.panjia.payroll.mapper.PolicyRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

/**
 * 积分规则查询适配器：从默认积分绩效方案（pj_payroll_perf_scheme，
 * GRADE_RATE + is_default）的 rule_content 读取积分口径，供积分域实时计算派生字段。
 * <p>
 * 2026-10 绩效方案化后积分等级阈值/扣点配置迁至方案表（员工档案按人指派），
 * 本适配器与算薪引擎 perfScheme 分支同源（同一默认方案），保证口径一致；
 * 默认方案缺失时回退 policy.points（兼容未执行 V160018 种子的库），再缺失返回 null
 * 由消费方（ScoreGradePolicy）兜底默认口径。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointsRuleQueryAdapter implements PointsRuleQueryPort {

    private final PerfSchemeMapper perfSchemeMapper;
    private final PolicyRuleMapper policyRuleMapper;
    private final ObjectMapper objectMapper;

    @Override
    public PointsRuleDTO pointsRule() {
        try {
            PerfScheme scheme = perfSchemeMapper.selectOne(new LambdaQueryWrapper<PerfScheme>()
                .eq(PerfScheme::getIsDefault, true)
                .eq(PerfScheme::getSchemeType, "GRADE_RATE")
                .last("LIMIT 1"));
            if (scheme != null && scheme.getRuleContent() != null && !scheme.getRuleContent().isBlank()) {
                JsonNode rc = objectMapper.readTree(scheme.getRuleContent());
                PointsRuleDTO dto = new PointsRuleDTO(
                    decimal(rc, "gradeA"),
                    decimal(rc, "gradeB"),
                    decimal(rc, "deductA"),
                    decimal(rc, "deductB"),
                    decimal(rc, "deductC"),
                    decimal(rc, "penaltyFee"));
                if (dto.getGradeAMin() != null) {
                    return dto;
                }
            }
        } catch (Exception e) {
            log.warn("[积分规则] 默认积分方案解析失败，回退 policy.points：{}", e.getMessage());
        }
        return legacyPolicyPoints();
    }

    /** 兼容回退：绩效方案缺失时读政策规则 points 节点（历史口径） */
    private PointsRuleDTO legacyPolicyPoints() {
        PolicyRule policy = policyRuleMapper.selectOne(new LambdaQueryWrapper<PolicyRule>()
            .eq(PolicyRule::getScopeType, "GLOBAL")
            .last("LIMIT 1"));
        if (policy == null || policy.getRuleContent() == null || policy.getRuleContent().isBlank()) {
            return null;
        }
        try {
            JsonNode points = objectMapper.readTree(policy.getRuleContent()).path("points");
            if (points.isMissingNode()) {
                return null;
            }
            return new PointsRuleDTO(
                decimal(points, "gradeA"),
                decimal(points, "gradeB"),
                decimal(points, "deductA"),
                decimal(points, "deductB"),
                decimal(points, "deductC"),
                decimal(points, "penaltyFee"));
        } catch (Exception e) {
            log.warn("[积分规则] 政策规则 points 节点解析失败，消费方将使用兜底口径：{}", e.getMessage());
            return null;
        }
    }

    private BigDecimal decimal(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? null : new BigDecimal(value.asText());
    }
}
