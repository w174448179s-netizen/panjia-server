package com.panjia.payroll.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.dto.PointsRuleDTO;
import com.panjia.contracts.port.PointsRuleQueryPort;
import com.panjia.payroll.domain.PolicyRule;
import com.panjia.payroll.mapper.PolicyRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

/**
 * 积分规则查询适配器：从政策规则表（pj_payroll_policy_rule GLOBAL）的
 * rule_content.points 节点读取积分口径，供积分域实时计算派生字段。
 * 与算薪引擎 resolvePerfDeduct 同源（同一条 GLOBAL 规则），保证口径一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointsRuleQueryAdapter implements PointsRuleQueryPort {

    private final PolicyRuleMapper policyRuleMapper;
    private final ObjectMapper objectMapper;

    @Override
    public PointsRuleDTO pointsRule() {
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
