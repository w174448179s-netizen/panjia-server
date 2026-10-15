package com.panjia.payroll.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ApprovalStartCmd;
import com.panjia.contracts.port.CommissionPerformanceQueryPort;
import com.panjia.contracts.port.PeopleQueryPort;
import com.panjia.contracts.port.PeopleSalaryFactSyncPort;
import com.panjia.contracts.snapshot.EmployeeSnapshot;
import com.panjia.payroll.domain.RankRule;
import com.panjia.payroll.mapper.RankRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职级自动晋升/降级服务（算薪时判定，新职级次月 1 日生效）。
 * <p>
 * 业务口径（已与客户确认，锁定）：
 * <ul>
 *   <li>A0（2500底薪+20%，保护期3个月）→ 保护期最后一个月算薪时判定：
 *       A0 期间累计业绩 &lt; 10000 → 次月进 A1；≥ 10000 → 次月直接晋 A2；</li>
 *   <li>A1 期间累计业绩 ≥ 10000 → 次月晋 A2；A2 期间累计业绩 ≥ 100000 → 次月晋 A3；</li>
 *   <li>累计业绩 = pj_perf_fact.performanceAmount 原始金额求和（不折算），
 *       区间 = 当前 LEVEL 事实 effective_date 所在月 ~ 算薪批次月（含）；</li>
 *   <li>新职级一律次月 1 日生效（写 pj_people_salary_fact 新 LEVEL 事实），
 *       本次算薪仍按旧职级——因此本服务须在算薪引擎执行之前调用；</li>
 *   <li>阈值/保护期/去向配置化：pj_payroll_rank_rule.rule_content.promotion，
 *       无 promotion 节点的职级不参与自动晋升。</li>
 * </ul>
 * 幂等：同一批次同一员工重复执行时，次月 1 日已存在相同目标职级事实则跳过
 * （由 {@link PeopleSalaryFactSyncPort#changeLevelFact} 保证）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionService {

    private final RankRuleMapper rankRuleMapper;
    private final PeopleQueryPort peopleQueryPort;
    private final PeopleSalaryFactSyncPort salaryFactSyncPort;
    private final CommissionPerformanceQueryPort performanceQueryPort;
    private final ApprovalPort approvalPort;
    private final ObjectMapper objectMapper;

    /** 一次职级变动结果（工号、原职级、新职级、生效日、累计业绩） */
    public record PromotionChange(Long employeeId, String employeeCode, String employeeName,
                                  String fromLevel, String toLevel,
                                  LocalDate effectiveDate, BigDecimal cumulativeAmount) {
    }

    /** rule_content.promotion 配置（protectMonths 为空 = 无保护期，每月算薪均判定） */
    private record PromotionCfg(Integer protectMonths, BigDecimal threshold, String belowTo, String reachTo) {
    }

    /**
     * 对本批次参与算薪的员工执行职级晋升/降级判定，写入次月 1 日生效的新 LEVEL 事实，
     * 并对实际发生的变更发起工作流待办通知（财务/总监）。
     *
     * @param batchId    算薪批次 ID（日志留痕用）
     * @param period     算薪归属月 YYYY-MM
     * @param employees  本批次参与算薪的员工快照（与算薪名单同口径）
     * @param operatorId 算薪操作人 ID
     * @return 实际发生的职级变更列表
     */
    public List<PromotionChange> checkAndApply(Long batchId, String period,
                                               List<EmployeeSnapshot> employees, Long operatorId) {
        if (employees == null || employees.isEmpty()) {
            return List.of();
        }
        YearMonth batchMonth = YearMonth.parse(period);
        LocalDate refDate = batchMonth.atDay(1);
        LocalDate nextMonthFirst = batchMonth.plusMonths(1).atDay(1);

        // 1. 批次月 1 日生效职级规则的 promotion 配置（无 promotion 节点的职级不参与）
        Map<String, PromotionCfg> cfgByLevel = loadPromotionCfg(refDate);
        if (cfgByLevel.isEmpty()) {
            return List.of();
        }

        // 2. 候选员工：当前职级持 promotion 配置
        Map<Long, EmployeeSnapshot> candidates = new LinkedHashMap<>();
        for (EmployeeSnapshot es : employees) {
            if (es.getEmployeeId() != null && es.getLevelCode() != null
                && cfgByLevel.containsKey(es.getLevelCode())) {
                candidates.put(es.getEmployeeId(), es);
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 3. 当前 LEVEL 事实生效月 = 累计窗口起点；保护期内（未满 protectMonths）不判定
        Map<Long, LocalDate> levelEffDates = peopleQueryPort.findLevelEffectiveDatesAt(candidates.keySet(), refDate);
        Map<Long, YearMonth> windowStart = new LinkedHashMap<>();
        YearMonth minStart = null;
        for (Map.Entry<Long, EmployeeSnapshot> e : candidates.entrySet()) {
            LocalDate eff = levelEffDates.get(e.getKey());
            if (eff == null) {
                continue;
            }
            YearMonth startMonth = YearMonth.from(eff);
            PromotionCfg cfg = cfgByLevel.get(e.getValue().getLevelCode());
            if (cfg.protectMonths() != null
                && ChronoUnit.MONTHS.between(startMonth, batchMonth) < cfg.protectMonths() - 1L) {
                continue;
            }
            windowStart.put(e.getKey(), startMonth);
            if (minStart == null || startMonth.isBefore(minStart)) {
                minStart = startMonth;
            }
        }
        if (windowStart.isEmpty()) {
            return List.of();
        }

        // 4. 累计业绩：pj_perf_fact 原始金额（不折算），各人窗口 [职级生效月, 批次月]
        Map<String, BigDecimal> monthly = performanceQueryPort.sumActiveByEmployeePeriods(
            windowStart.keySet(), minStart.toString(), period);
        Map<Long, BigDecimal> cumulative = new HashMap<>();
        for (Map.Entry<Long, YearMonth> e : windowStart.entrySet()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (YearMonth m = e.getValue(); !m.isAfter(batchMonth); m = m.plusMonths(1)) {
                sum = sum.add(monthly.getOrDefault(e.getKey() + "|" + m, BigDecimal.ZERO));
            }
            cumulative.put(e.getKey(), sum);
        }

        // 5. 判定去向 → 写次月 1 日 LEVEL 事实（幂等）→ 工作流待办通知
        List<PromotionChange> changes = new ArrayList<>();
        for (Map.Entry<Long, YearMonth> e : windowStart.entrySet()) {
            EmployeeSnapshot es = candidates.get(e.getKey());
            PromotionCfg cfg = cfgByLevel.get(es.getLevelCode());
            BigDecimal cum = cumulative.get(e.getKey());
            boolean reach = cum.compareTo(cfg.threshold()) >= 0;
            String target = reach ? cfg.reachTo() : cfg.belowTo();
            if (target == null || target.equals(es.getLevelCode())) {
                continue;
            }
            boolean changed = salaryFactSyncPort.changeLevelFact(e.getKey(), target, nextMonthFirst, operatorId);
            if (!changed) {
                continue;
            }
            PromotionChange change = new PromotionChange(e.getKey(), es.getEmployeeCode(), es.getEmployeeName(),
                es.getLevelCode(), target, nextMonthFirst, cum);
            changes.add(change);
            notifyChange(batchId, period, batchMonth, change, operatorId);
        }
        return changes;
    }

    /**
     * 职级变动待办通知：发起 payroll_promotion 流程（财务/总监角色知会节点，
     * 办理即流程结束，无业务状态回写）。通知发起失败不阻断主流程，仅留 error 日志。
     */
    private void notifyChange(Long batchId, String period, YearMonth batchMonth,
                              PromotionChange c, Long operatorId) {
        try {
            String title = "职级变动通知｜" + c.employeeName() + "(" + c.employeeCode() + ")｜"
                + c.fromLevel() + "→" + c.toLevel() + "｜"
                + batchMonth.plusMonths(1) + "生效｜累计业绩" + c.cumulativeAmount();
            ApprovalStartCmd cmd = ApprovalStartCmd.of(period + "-" + c.employeeCode(), title);
            cmd.setHandler(String.valueOf(operatorId));
            Map<String, Object> variables = new HashMap<>(2);
            variables.put("ignore", true);
            cmd.setVariables(variables);
            // bizId 用独立雪花 ID：与 payroll_batch 流程实例（businessId=batchId）隔离，避免按业务单号反查串单
            approvalPort.start(BizType.PAYROLL_PROMOTION, IdWorker.getId(), cmd);
        } catch (Exception e) {
            log.error("[职级晋升] 待办通知发起失败：batchId={}, period={}, emp={}({}), {}→{}",
                batchId, period, c.employeeName(), c.employeeCode(), c.fromLevel(), c.toLevel(), e);
        }
    }

    /**
     * 取批次月 1 日生效的职级规则并解析 rule_content.promotion 节点。
     * 生效版本判定与 {@link RuleService} 快照口径一致
     * （effective_from &lt;= refDate &lt;= effective_to，同职级区间不重叠）。
     */
    private Map<String, PromotionCfg> loadPromotionCfg(LocalDate refDate) {
        Map<String, List<RankRule>> byLevel = new HashMap<>();
        for (RankRule r : rankRuleMapper.selectList(null)) {
            byLevel.computeIfAbsent(r.getLevelCode(), k -> new ArrayList<>()).add(r);
        }
        Map<String, PromotionCfg> result = new HashMap<>();
        for (Map.Entry<String, List<RankRule>> entry : byLevel.entrySet()) {
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
            if (hit == null || hit.getRuleContent() == null) {
                continue;
            }
            PromotionCfg cfg = parsePromotion(hit.getRuleContent());
            if (cfg != null) {
                result.put(entry.getKey(), cfg);
            }
        }
        return result;
    }

    /** 解析 rule_content.promotion；无节点或缺 threshold/reachTo 返回 null（不参与自动晋升） */
    private PromotionCfg parsePromotion(String ruleContent) {
        try {
            JsonNode node = objectMapper.readTree(ruleContent).path("promotion");
            if (node.isMissingNode() || node.isNull()) {
                return null;
            }
            JsonNode thresholdNode = node.path("threshold");
            JsonNode reachToNode = node.path("reachTo");
            if (thresholdNode.isMissingNode() || thresholdNode.isNull()
                || reachToNode.asText("").isBlank()) {
                return null;
            }
            JsonNode protectNode = node.path("protectMonths");
            Integer protectMonths = protectNode.isMissingNode() || protectNode.isNull() ? null : protectNode.asInt();
            String belowTo = node.path("belowTo").asText("");
            if (belowTo.isBlank()) {
                belowTo = null;
            }
            return new PromotionCfg(protectMonths, new BigDecimal(thresholdNode.asText("0")),
                belowTo, reachToNode.asText(""));
        } catch (Exception e) {
            log.warn("[职级晋升] promotion 配置解析失败：{}", ruleContent, e);
            return null;
        }
    }
}
