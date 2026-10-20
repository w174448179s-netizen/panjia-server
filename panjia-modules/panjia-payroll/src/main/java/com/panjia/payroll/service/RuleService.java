package com.panjia.payroll.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.panjia.payroll.domain.ConversionRule;
import com.panjia.payroll.domain.PolicyRule;
import com.panjia.payroll.domain.PerfScheme;
import com.panjia.payroll.domain.RankRule;
import com.panjia.payroll.domain.RuleSnapshot;
import com.panjia.payroll.mapper.ConversionRuleMapper;
import com.panjia.payroll.mapper.PolicyRuleMapper;
import com.panjia.payroll.mapper.PerfSchemeMapper;
import com.panjia.payroll.mapper.RankRuleMapper;
import com.panjia.payroll.mapper.RuleSnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final PerfSchemeMapper perfSchemeMapper;
    private final ObjectMapper objectMapper;

    // ==================== 规则 CRUD ====================

    public List<RankRule> listRankRules() {
        return rankRuleMapper.selectList(null);
    }

    public List<PolicyRule> listPolicyRules() {
        return policyRuleMapper.selectList(null);
    }

    /**
     * 取当前生效的员工级政策覆盖（EMPLOYEE scope）涉及的员工工号集合。
     * <p>算薪名单扩展用：仅有社保/公积金等个人政策覆盖的员工（当月无业绩、
     * 无手工项）也应进名单完成当月结算，否则个人代扣会整月漏算。
     */
    public Set<String> employeePolicyScopeKeys() {
        LocalDate today = LocalDate.now();
        Set<String> keys = new HashSet<>();
        for (PolicyRule p : policyRuleMapper.selectList(null)) {
            if (!"EMPLOYEE".equals(p.getScopeType())) {
                continue;
            }
            if (p.getEffectiveFrom() != null && p.getEffectiveFrom().isAfter(today)) {
                continue;
            }
            if (p.getEffectiveTo() != null && p.getEffectiveTo().isBefore(today)) {
                continue;
            }
            if (p.getScopeKey() != null && !p.getScopeKey().isBlank()) {
                keys.add(p.getScopeKey());
            }
        }
        return keys;
    }

    public List<ConversionRule> listConversionRules() {
        return conversionRuleMapper.selectList(null);
    }

    // 注：折算比例取数已统一收敛到 ConversionFactorPort（唯一公共出口，实现在 payroll.adapter），
    // 工资明细页与业务明细页共用同一口径，此处不再重复实现。

    public void saveRankRule(RankRule rule) {
        if (rule.getEffectiveFrom() == null) {
            rule.setEffectiveFrom(LocalDate.now());
        }
        if (rule.getId() == null) {
            rankRuleMapper.insert(rule);
        } else {
            rankRuleMapper.updateById(rule);
        }
    }

    public void savePolicyRule(PolicyRule rule) {
        if (rule.getEffectiveFrom() == null) {
            rule.setEffectiveFrom(LocalDate.now());
        }
        if (rule.getId() == null) {
            policyRuleMapper.insert(rule);
        } else {
            policyRuleMapper.updateById(rule);
        }
    }

    public void saveConversionRule(ConversionRule rule) {
        if (rule.getEffectiveFrom() == null) {
            rule.setEffectiveFrom(LocalDate.now());
        }
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
        ObjectNode root = buildSnapshotRoot(today);

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

    /**
     * 按指定参考日期构建规则快照（不持久化，供算薪时多期间规则加载使用）。
     * <p>
     * 与 freezeSnapshot 逻辑相同，但用 refDate 代替 LocalDate.now() 过滤生效规则，
     * 且不写入 RuleSnapshot 表。
     *
     * @param refDate 规则生效的参考日期（如结算月首日 / 签约月首日）
     * @return 解析后的规则快照
     */
    public ParsedSnapshot buildSnapshotForDate(LocalDate refDate) {
        ObjectNode root = buildSnapshotRoot(refDate);
        validateRuleCompleteness(root);
        return new ParsedSnapshot(root);
    }

    /**
     * 规则配置完整性校验：敏感配置缺失时记录警告（算薪仍按引擎默认值执行，避免静默错账无人知）。
     * personalRate 仅店长/总监提成使用；伯乐奖励费率/上限/比例按配置的模式校验；
     * 绩效等级阈值取 policy.points；系统算税（ENGINE）时必须有税率表。
     */
    private void validateRuleCompleteness(JsonNode root) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        JsonNode ranks = root.path("rank");
        if (ranks.isObject()) {
            ranks.properties().forEach(e -> {
                String level = e.getKey();
                JsonNode rank = e.getValue();
                if (!rank.hasNonNull("personalRate")) {
                    missing.add("职级[" + level + "] personalRate（店长/总监个人提点）未配置，将按引擎默认 0.70 计算");
                }
                JsonNode rc = rank.path("ruleContent");
                String mode = rc.path("mentorBonusMode").asText("");
                if ("RATE_ADD".equals(mode)) {
                    if (!rc.hasNonNull("mentorBonusRateAdd")) {
                        missing.add("职级[" + level + "] mentorBonusRateAdd 未配置，将按默认 0.02");
                    }
                    if (!rc.hasNonNull("mentorBonusRateCap")) {
                        missing.add("职级[" + level + "] mentorBonusRateCap 未配置，将按默认 0.10");
                    }
                } else if ("AMOUNT_RATIO".equals(mode)) {
                    if (!rc.hasNonNull("mentorBonusAmountRatio")) {
                        missing.add("职级[" + level + "] mentorBonusAmountRatio 未配置，将按默认 0.02");
                    }
                }
            });
        }
        // 积分等级阈值已迁至绩效方案（perfScheme 节点）：默认积分方案缺失时按引擎兜底 8/6 计算
        JsonNode defaultScheme = root.path("perfScheme").path(root.path("defaultPerfScheme").asText(""));
        if (defaultScheme.isMissingNode()) {
            missing.add("默认绩效方案（积分绩效）未配置，员工未指派方案时将按引擎默认阈值 8/6 计算");
        } else if ("GRADE_RATE".equals(defaultScheme.path("schemeType").asText())
            && !defaultScheme.path("ruleContent").hasNonNull("gradeA")) {
            missing.add("默认积分方案 gradeA（绩效A阈值）未配置，将按默认 8");
        }
        if ("ENGINE".equalsIgnoreCase(root.path("policy").path("tax").path("mode").asText("EXTERNAL"))
            && root.path("policy").path("tax").path("brackets").size() == 0) {
            missing.add("policy.tax.brackets 税率表未配置（tax.mode=ENGINE 系统算税必须有税率表）");
        }
        if (!missing.isEmpty()) {
            log.warn("[薪酬] 规则快照配置不完整，算薪将使用引擎默认值：{}", String.join("；", missing));
        }
    }

    /**
     * 构建规则 JSON 根节点（rank + policy + conversion）。
     *
     * @param refDate 规则生效参考日期
     */
    private ObjectNode buildSnapshotRoot(LocalDate refDate) {
        List<RankRule> ranks = rankRuleMapper.selectList(null);
        List<PolicyRule> policies = policyRuleMapper.selectList(null);
        List<ConversionRule> conversions = conversionRuleMapper.selectList(null);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("snapshotVersion", refDate.toString());

        // rank: levelCode -> {baseSalary, baseRate, minSalary, teamRate, personalRate, ruleContent}
        // 生效区间未覆盖 refDate 的职级：回退到最近一版（先取 effectiveFrom<=refDate 的最新版，
        // 否则取最早的未来版），标记 fallback=true 并汇总 rankFallbacks 供预警，
        // 避免员工职级有效但规则全部失效时被静默按 0 提点/0 底薪计算
        ObjectNode rankNode = root.putObject("rank");
        ArrayNode rankFallbacks = root.putArray("rankFallbacks");
        Map<String, List<RankRule>> ranksByLevel = new HashMap<>();
        for (RankRule r : ranks) {
            ranksByLevel.computeIfAbsent(r.getLevelCode(), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<RankRule>> entry : ranksByLevel.entrySet()) {
            List<RankRule> versions = entry.getValue();
            versions.sort(Comparator.comparing(RankRule::getEffectiveFrom));
            RankRule hit = null;
            for (RankRule r : versions) {
                if (!r.getEffectiveFrom().isAfter(refDate)
                    && (r.getEffectiveTo() == null || !r.getEffectiveTo().isBefore(refDate))) {
                    hit = r;
                    break;
                }
            }
            boolean fallback = false;
            if (hit == null) {
                fallback = true;
                for (int i = versions.size() - 1; i >= 0; i--) {
                    if (!versions.get(i).getEffectiveFrom().isAfter(refDate)) {
                        hit = versions.get(i);
                        break;
                    }
                }
                if (hit == null) {
                    hit = versions.get(0); // 全部为未来版本：取最早生效的一版
                }
                rankFallbacks.add(entry.getKey());
            }
            ObjectNode rn = rankNode.putObject(entry.getKey());
            rn.put("baseSalary", hit.getBaseSalary().toString());
            rn.put("baseRate", hit.getBaseRate().toString());
            rn.put("minSalary", hit.getMinSalary().toString());
            if (hit.getTeamRate() != null) rn.put("teamRate", hit.getTeamRate().toString());
            if (hit.getPersonalRate() != null) rn.put("personalRate", hit.getPersonalRate().toString());
            if (fallback) rn.put("fallback", true);
            try {
                rn.set("ruleContent", objectMapper.readTree(hit.getRuleContent() == null ? "{}" : hit.getRuleContent()));
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

        // dept overrides merged into policy.deptOverride（key=deptId；门店社保扣减标准 socialStandard 等）
        ObjectNode deptOverride = policyNode.has("deptOverride")
            ? (ObjectNode) policyNode.get("deptOverride") : policyNode.putObject("deptOverride");
        for (PolicyRule p : policies) {
            if (!"DEPT".equals(p.getScopeType()) || p.getScopeKey() == null) {
                continue;
            }
            if (p.getEffectiveFrom() != null && p.getEffectiveFrom().isAfter(refDate)) {
                continue;
            }
            if (p.getEffectiveTo() != null && p.getEffectiveTo().isBefore(refDate)) {
                continue;
            }
            try {
                ObjectNode merged = deptOverride.has(p.getScopeKey())
                    ? (ObjectNode) deptOverride.get(p.getScopeKey()) : objectMapper.createObjectNode();
                JsonNode incoming = objectMapper.readTree(p.getRuleContent() == null ? "{}" : p.getRuleContent());
                merged.setAll((ObjectNode) incoming);
                deptOverride.set(p.getScopeKey(), merged);
            } catch (Exception e) {
                log.warn("dept policy rule content parse failed, scopeKey={}", p.getScopeKey(), e);
            }
        }

        // conversion: bizType -> factor
        ObjectNode convNode = root.putObject("conversion");
        for (ConversionRule c : conversions) {
            convNode.put(c.getBizType(), c.getFactor().toString());
        }

        // perfScheme: code -> {name, schemeType, applicableRole, isDefault, ruleContent}
        // 仅装载算薪日已生效方案（effective_from <= refDate）；员工未指派（PERF_SCHEME 事实空）
        // 按 defaultPerfScheme（is_default 的积分绩效）计算，保证历史批次重算口径不变
        List<PerfScheme> schemes = perfSchemeMapper.selectList(null);
        ObjectNode schemeNode = root.putObject("perfScheme");
        String defaultSchemeCode = null;
        for (PerfScheme s : schemes) {
            if (s.getEffectiveFrom() != null && s.getEffectiveFrom().isAfter(refDate)) {
                continue;
            }
            ObjectNode sn = schemeNode.putObject(s.getCode());
            sn.put("name", s.getName());
            sn.put("schemeType", s.getSchemeType());
            sn.put("applicableRole", s.getApplicableRole());
            sn.put("isDefault", Boolean.TRUE.equals(s.getIsDefault()));
            try {
                sn.set("ruleContent",
                    objectMapper.readTree(s.getRuleContent() == null ? "{}" : s.getRuleContent()));
            } catch (Exception e) {
                sn.putObject("ruleContent");
            }
            if (defaultSchemeCode == null && Boolean.TRUE.equals(s.getIsDefault())) {
                defaultSchemeCode = s.getCode();
            }
        }
        if (defaultSchemeCode != null) {
            root.put("defaultPerfScheme", defaultSchemeCode);
        }

        return root;
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

        /**
         * 门店级政策覆盖（policy.deptOverride[deptId]），如门店社保扣减标准。
         */
        public JsonNode deptOverride(Long deptId) {
            return root.path("policy").path("deptOverride").path(String.valueOf(deptId));
        }

        /**
         * 门店社保扣减标准（每人每月固定额，DEPT 政策 socialStandard），未配置返回 0。
         */
        public java.math.BigDecimal deptSocialStandard(Long deptId) {
            JsonNode n = deptOverride(deptId).path("socialStandard");
            if (n.isMissingNode() || n.isNull()) {
                return java.math.BigDecimal.ZERO;
            }
            return new java.math.BigDecimal(n.asText("0"));
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

        // ==================== 绩效方案 ====================

        /** 默认绩效方案编码（员工未指派 PERF_SCHEME 事实时使用；快照无默认时返回 null） */
        public String defaultPerfSchemeCode() {
            JsonNode n = root.path("defaultPerfScheme");
            return n.isMissingNode() || n.isNull() ? null : n.asText();
        }

        /** 按编码取方案节点（含 name/schemeType/applicableRole/isDefault/ruleContent） */
        public JsonNode perfScheme(String code) {
            return root.path("perfScheme").path(code);
        }

        /**
         * 解析员工生效方案节点：员工指派编码命中 → 该方案；
         * 未指派/编码无效 → 默认方案；默认也缺失 → missing node（引擎兜底积分口径）。
         */
        public JsonNode resolvePerfScheme(String empSchemeCode) {
            if (empSchemeCode != null && !empSchemeCode.isBlank()) {
                JsonNode hit = perfScheme(empSchemeCode);
                if (!hit.isMissingNode()) {
                    return hit;
                }
            }
            String def = defaultPerfSchemeCode();
            return def == null ? objectMapperNull() : perfScheme(def);
        }

        private JsonNode objectMapperNull() {
            return tools.jackson.databind.node.JsonNodeFactory.instance.missingNode();
        }
    }

    // ==================== 绩效方案 CRUD ====================

    public List<PerfScheme> listPerfSchemes() {
        return perfSchemeMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PerfScheme>()
                .orderByAsc(PerfScheme::getCode));
    }

    /**
     * 保存方案（code 存在则更新，否则新增）。
     * 校验：code/name/schemeType 必填、类型合法、默认方案唯一（新默认会清掉旧默认）。
     */
    public void savePerfScheme(PerfScheme scheme) {
        if (scheme.getCode() == null || scheme.getCode().isBlank()) {
            throw new IllegalArgumentException("方案编码不能为空");
        }
        if (scheme.getName() == null || scheme.getName().isBlank()) {
            throw new IllegalArgumentException("方案名称不能为空");
        }
        String type = scheme.getSchemeType();
        if (type == null || java.util.Set.of("NONE", "GRADE_RATE", "RATE_TIER", "AMOUNT_TIER", "GRADE_AMOUNT")
            .contains(type) == false) {
            throw new IllegalArgumentException("非法方案类型：" + type);
        }
        String role = scheme.getApplicableRole();
        scheme.setApplicableRole(role == null || role.isBlank() ? "ALL" : role);

        PerfScheme existing = perfSchemeMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PerfScheme>()
                .eq(PerfScheme::getCode, scheme.getCode().trim())
                .last("LIMIT 1"));
        scheme.setCode(scheme.getCode().trim());
        if (Boolean.TRUE.equals(scheme.getIsDefault())) {
            // 默认方案唯一：清掉其他默认标记
            List<PerfScheme> defaults = perfSchemeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PerfScheme>()
                    .eq(PerfScheme::getIsDefault, true));
            for (PerfScheme d : defaults) {
                if (existing == null || !d.getId().equals(existing.getId())) {
                    d.setIsDefault(false);
                    perfSchemeMapper.updateById(d);
                }
            }
        }
        if (existing != null) {
            scheme.setId(existing.getId());
            perfSchemeMapper.updateById(scheme);
        } else {
            if (scheme.getEffectiveFrom() == null) {
                scheme.setEffectiveFrom(LocalDate.now());
            }
            perfSchemeMapper.insert(scheme);
        }
    }
}
