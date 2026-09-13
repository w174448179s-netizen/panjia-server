package com.panjia.payroll.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.panjia.payroll.domain.ConversionRule;
import com.panjia.payroll.domain.PolicyRule;
import com.panjia.payroll.domain.RankRule;
import com.panjia.payroll.domain.RuleSnapshot;
import com.panjia.payroll.mapper.ConversionRuleMapper;
import com.panjia.payroll.mapper.PolicyRuleMapper;
import com.panjia.payroll.mapper.RankRuleMapper;
import com.panjia.payroll.mapper.RuleSnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 薪酬规则服务（职级/政策/折算三类规则 + 快照冻结）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleService {

    private final RankRuleMapper rankRuleMapper;
    private final PolicyRuleMapper policyRuleMapper;
    private final ConversionRuleMapper conversionRuleMapper;
    private final RuleSnapshotMapper ruleSnapshotMapper;
    private final ObjectMapper objectMapper;

    // ==================== 规则 CRUD ====================

    public List<RankRule> listRankRules() {
        return rankRuleMapper.selectList(null);
    }

    public List<PolicyRule> listPolicyRules() {
        return policyRuleMapper.selectList(null);
    }

    public List<ConversionRule> listConversionRules() {
        return conversionRuleMapper.selectList(null);
    }

    public void saveRankRule(RankRule rule) {
        if (rule.getId() == null) {
            rankRuleMapper.insert(rule);
        } else {
            rankRuleMapper.updateById(rule);
        }
    }

    public void savePolicyRule(PolicyRule rule) {
        if (rule.getId() == null) {
            policyRuleMapper.insert(rule);
        } else {
            policyRuleMapper.updateById(rule);
        }
    }

    public void saveConversionRule(ConversionRule rule) {
        if (rule.getId() == null) {
            conversionRuleMapper.insert(rule);
        } else {
            conversionRuleMapper.updateById(rule);
        }
    }

    // ==================== 快照冻结 ====================

    /**
     * 取当前生效规则并合并冻结为 JSON（批次算薪时调用，与批次同事务）。
     */
    public RuleSnapshot freezeSnapshot(Long batchId, String period) {
        LocalDate today = LocalDate.now();
        List<RankRule> ranks = rankRuleMapper.selectList(null);
        List<PolicyRule> policies = policyRuleMapper.selectList(null);
        List<ConversionRule> conversions = conversionRuleMapper.selectList(null);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("snapshotVersion", today.toString());

        // rank: levelCode -> {baseSalary, baseRate, minSalary, teamRate, personalRate, ruleContent}
        ObjectNode rankNode = root.putObject("rank");
        for (RankRule r : ranks) {
            if (r.getEffectiveFrom().isAfter(today) || r.getEffectiveTo().isBefore(today)) {
                continue;
            }
            ObjectNode rn = rankNode.putObject(r.getLevelCode());
            rn.put("baseSalary", r.getBaseSalary().toString());
            rn.put("baseRate", r.getBaseRate().toString());
            rn.put("minSalary", r.getMinSalary().toString());
            if (r.getTeamRate() != null) rn.put("teamRate", r.getTeamRate().toString());
            if (r.getPersonalRate() != null) rn.put("personalRate", r.getPersonalRate().toString());
            try {
                rn.set("ruleContent", objectMapper.readTree(r.getRuleContent() == null ? "{}" : r.getRuleContent()));
            } catch (Exception e) {
                rn.putObject("ruleContent");
            }
        }

        // policy: GLOBAL first, then TAG, then EMPLOYEE overrides
        ObjectNode policyNode = root.putObject("policy");
        PolicyRule global = null;
        for (PolicyRule p : policies) {
            if ("GLOBAL".equals(p.getScopeType())) {
                global = p;
                break;
            }
        }
        if (global != null) {
            try {
                policyNode.setAll((ObjectNode) objectMapper.readTree(global.getRuleContent()));
            } catch (Exception e) {
                log.warn("policy rule content parse failed", e);
            }
            policyNode.put("baseSocial", global.getBaseSocial().toString());
        }
        // employee overrides merged into policy.employeeOverride
        ObjectNode empOverride = policyNode.has("employeeOverride")
            ? (ObjectNode) policyNode.get("employeeOverride") : policyNode.putObject("employeeOverride");
        for (PolicyRule p : policies) {
            if ("EMPLOYEE".equals(p.getScopeType()) && p.getScopeKey() != null) {
                try {
                    empOverride.set(p.getScopeKey(), objectMapper.readTree(p.getRuleContent()));
                } catch (Exception ignored) {
                }
            }
        }

        // conversion: bizType -> factor
        ObjectNode convNode = root.putObject("conversion");
        for (ConversionRule c : conversions) {
            convNode.put(c.getBizType(), c.getFactor().toString());
        }

        RuleSnapshot snapshot = new RuleSnapshot();
        snapshot.setBatchId(batchId);
        snapshot.setPeriod(period);
        snapshot.setSnapshotContent(root.toString());
        // 重复算薪时先删旧快照（batch_id 唯一约束）
        ruleSnapshotMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RuleSnapshot>()
            .eq(RuleSnapshot::getBatchId, batchId));
        ruleSnapshotMapper.insert(snapshot);
        return snapshot;
    }

    public RuleSnapshot getSnapshot(Long batchId) {
        return ruleSnapshotMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RuleSnapshot>()
                .eq(RuleSnapshot::getBatchId, batchId));
    }

    /**
     * 解析规则快照为可计算的结构（Map 形式，避免强依赖 JSON 结构）。
     */
    public ParsedSnapshot parseSnapshot(String snapshotContent) {
        try {
            JsonNode root = objectMapper.readTree(snapshotContent);
            return new ParsedSnapshot(root);
        } catch (Exception e) {
            throw new RuntimeException("规则快照解析失败", e);
        }
    }

    /** 解析后的规则快照（只读视图） */
    public static class ParsedSnapshot {
        private final JsonNode root;

        public ParsedSnapshot(JsonNode root) {
            this.root = root;
        }

        public JsonNode rank(String levelCode) {
            return root.path("rank").path(levelCode);
        }

        public JsonNode policy() {
            return root.path("policy");
        }

        public JsonNode employeeOverride(String employeeCode) {
            return root.path("policy").path("employeeOverride").path(employeeCode);
        }

        public java.math.BigDecimal conversionFactor(String bizType) {
            JsonNode n = root.path("conversion").path(bizType);
            if (n.isMissingNode() || n.isNull()) {
                n = root.path("conversion").path("DEFAULT");
            }
            return new java.math.BigDecimal(n.asText("1"));
        }

        public JsonNode socialRatio() {
            return policy().path("socialSettlementRatio");
        }

        public JsonNode tax() {
            return policy().path("tax");
        }
    }
}
