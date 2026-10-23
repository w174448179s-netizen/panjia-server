package com.panjia.payroll.service;

import tools.jackson.databind.JsonNode;
import com.panjia.contracts.dto.AttendanceMetricsDTO;
import com.panjia.contracts.dto.CommissionItemDTO;
import com.panjia.contracts.dto.ScoreFactsDTO;
import com.panjia.contracts.snapshot.EmployeeSnapshot;
import com.panjia.payroll.domain.EmployeeRole;
import com.panjia.payroll.domain.PayrollDetail;
import com.panjia.payroll.dto.RateAdjustItem;
import com.panjia.payroll.util.MoneyUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 算薪引擎（纯计算、无副作用）。
 * <p>
 * 输入不可变 → 输出不可变。所有金额全程 BigDecimal，只在最终落地取整。
 * 依据《薪酬结算域详细设计 V1.0》§2.6 算薪顺序。
 */
@Slf4j
@Component
public class SalaryCalculationEngine {

    /** 招聘加点上限 +10% */
    private static final BigDecimal MENTOR_CAP = new BigDecimal("0.10");

    /** 门店计缴参保人数比例阈值（个人社保比例严格 > 30% 才计入） */
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal THIRTY = new BigDecimal("30");

    /**
     * 算薪输入。
     */
    public static class CalcInput {
        public List<EmployeeSnapshot> employees;
        /** 算薪归属期间（YYYY-MM）：日工资按当月自然天数计算，入职当月底薪按入职日至月底折算 */
        public String period;
        /** employeeId -> 已审批结佣明细（PERF_REAL，原样取用不折算：P5/S-14/P15） */
        public Map<Long, List<CommissionItemDTO>> lockedByEmp;
        /** employeeId -> 新签业绩明细（PERF_EXPECT，折算） */
        public Map<Long, List<CommissionItemDTO>> newsignByEmp;
        /** deptId -> 门店新签计薪业绩合计（已折算） */
        public Map<Long, BigDecimal> deptNewSignTotal;
        /** deptId -> 门店社保业绩扣款（门店社保扣减标准 × 计缴参保人数，店长/总监计薪基数扣减项） */
        public Map<Long, BigDecimal> deptEmployerSocialTotal;
        /** deptId -> 门店社保扣减标准（每人每月固定额，DEPT 政策 socialStandard） */
        public Map<Long, BigDecimal> deptSocialStandard;
        /** deptId -> 门店计缴参保人数（非兼职+参保+个人社保比例>30%） */
        public Map<Long, Integer> deptInsuredCount;
        /** deptId -> 门店当月新签与结佣差额（门店月度配置，未配置=0，计薪基数直接扣减项） */
        public Map<Long, BigDecimal> deptDiffTotal;

        // ===== 原始口径（不做「不计入店长团队」排除，2026-10-19 拆分：总监管辖组跳点用原始数，
        // 店长团队提成用上方排除后口径；店长/总监同组数字允许不同，开关语义=只影响店长） =====
        /** deptId -> 组新签合计（原始，含被排除员工） */
        public Map<Long, BigDecimal> deptNewSignTotalRaw;
        /** deptId -> 社保业绩扣款（原始人数口径） */
        public Map<Long, BigDecimal> deptEmployerSocialTotalRaw;
        /** 算薪期间的规则快照（主快照） */
        public RuleService.ParsedSnapshot snapshot;
        /** factId → 新签导入月（结佣提成规则期间：绑新签事实取其 period；实收明细按业务键匹配新签事实） */
        public Map<Long, String> factPeriodMap = Map.of();
        /** 新签导入月 → 员工ID → 该月历史职级（结佣提成按新签月职级算提点；未命中回退算薪月职级） */
        public Map<String, Map<Long, String>> periodLevelMap = Map.of();
        /** 新签导入月 → 员工ID → 该月历史绩效事实（结佣按新签月积分派生等级；该月无数据按 0 分处理） */
        public Map<String, Map<Long, ScoreFactsDTO>> periodScoreMap = Map.of();
        /** 新签导入月 → 伯乐员工ID → 当月生效千里马数（招聘加点按新签月伯乐切片；未命中回退算薪月） */
        public Map<String, Map<Long, Integer>> periodApprenticeMap = Map.of();
        /** 新签导入月 → 员工ID → 该月当时生效的人工调整单（按新签月口径；该月无调整=不叠加） */
        public Map<String, Map<Long, java.util.List<RateAdjustItem>>> periodAdjustMap = Map.of();
        /**
         * 多期间规则快照缓存：key=规则期间（YYYY-MM），value=该期间的 ParsedSnapshot。
         * <p>
         * 结佣提成统一按<b>新签导入月</b>（对应新签业绩事实的归属月，非签约日所在月、
         * 非结佣导入月）取规则快照（2026-10-17 改）。本 Map 由 PayrollBatchService 在算薪前
         * 扫描结佣明细预先构建，engine 按期间精确命中；key 缺失时回退到主 snapshot。
         */
        public Map<String, RuleService.ParsedSnapshot> periodSnapshots = Map.of();
        /** employeeId -> 奖金（手工项 itemType=BONUS） */
        public Map<Long, BigDecimal> bonusIncome;
        /** employeeId -> 其他收入（手工项 itemType=OTHER_INCOME） */
        public Map<Long, BigDecimal> otherIncomeMap;
        /** employeeId -> 手工支出 */
        public Map<Long, BigDecimal> manualDeduct;
        /** employeeId -> 负工资结转待扣 */
        public Map<Long, BigDecimal> negativeBalance;
        /** employeeId -> 累计已预扣个税（当年） */
        public Map<Long, BigDecimal> cumulativeTax;
        /** employeeId -> 累计应纳税所得额（当年） */
        public Map<Long, BigDecimal> cumulativeTaxable;
        /** employeeId -> 本年任职月数 */
        public Map<Long, Integer> monthsEmployed;
        /** employeeId -> 考勤月度指标 - 旧扁平模板导入金额 importedFee + 迟到/旷工/请假，按 policy.attendance 规则计算扣款。
         * 端口只装事实（AttendanceMetricsDTO），不预先按规则算扣款——与同模块 ScoreFactsDTO 同模式。 */
        public Map<Long, AttendanceMetricsDTO> attendanceMetrics;
        /** employeeId -> 绩效事实（总积分/出勤天数/晚提交次数），引擎按 policy.points 规则
         * 派生 grade 与 pointsFee；不在端口提前算好。 */
        public Map<Long, ScoreFactsDTO> scoreFacts;
        /** employeeId -> 提成点人工调整命中项（rate_adjust APPROVED 且区间命中，溯源写入 rate_adjust_json） */
        public Map<Long, List<RateAdjustItem>> manualAdjustItems;
        /** employeeId -> 合格千里马数（伯乐奖励加点用） */
        public Map<Long, Integer> qualifiedApprenticeCount;
        /** employeeId -> 总监管辖门店 deptId 列表（含自身及子孙，总监多门店按门店分别跳点算提成） */
        public Map<Long, List<Long>> directorStoreDepts;
        /** deptId -> 部门展示名（总监门店提成明细 deptName 用） */
        public Map<Long, String> deptNames;
    }

    /**
     * 执行算薪，返回工资明细列表（内存对象，未落库）。
     */
    public List<PayrollDetail> calculate(CalcInput input) {
        List<PayrollDetail> details = new ArrayList<>();
        RuleService.ParsedSnapshot snap = input.snapshot;

        // 伯乐奖励数据源预算（AMOUNT_RATIO，店长/总监）：伯乐 → 千里马结佣业绩合计（主快照折算系数）。
        // applyConversion 为纯函数，可主循环前预算；伯乐归属按算薪月快照 mentorId（伯乐号生效区间当前值）
        Map<Long, BigDecimal> apprenticePerfByMentor = new HashMap<>();
        for (EmployeeSnapshot t : input.employees) {
            if (t.getMentorId() == null) {
                continue;
            }
            List<CommissionItemDTO> tItems = input.lockedByEmp == null ? null : input.lockedByEmp.get(t.getEmployeeId());
            if (tItems == null || tItems.isEmpty()) {
                continue;
            }
            apprenticePerfByMentor.merge(t.getMentorId(), applyConversion(tItems, snap), BigDecimal::add);
        }

        for (EmployeeSnapshot emp : input.employees) {
            PayrollDetail d = new PayrollDetail();
            d.setEmployeeId(emp.getEmployeeId());
            d.setDeptId(emp.getDeptId());
            d.setLevelCode(emp.getLevelCode());
            d.setIsPartTime(Boolean.TRUE.equals(emp.getIsPartTime()));

            EmployeeRole role = resolveRole(emp);
            d.setEmployeeRole(role);

            String level = emp.getLevelCode() == null ? "A0" : emp.getLevelCode();
            JsonNode rank = snap.rank(level);
            if (rank.path("fallback").asBoolean(false)) {
                log.warn("员工 {} 职级 {} 在算薪日无生效规则，已按该职级最近一版规则兜底",
                    emp.getEmployeeCode(), level);
            }

            // 基础提点
            BigDecimal baseRate = bd(rank.path("baseRate").asText("0"));

            // 当月新签业绩（折算后，所有角色通用）——先算供绩效方案阶梯定档与明细落库复用
            BigDecimal newSignPerfConverted = applyConversion(input.newsignByEmp.get(emp.getEmployeeId()), snap);

            // 绩效方案（2026-10 方案化：员工按 PERF_SCHEME 事实指派，未指派=默认方案积分绩效）：
            // NONE→不考核；GRADE_RATE 积分绩效（等级扣点，历史口径）；RATE_TIER/AMOUNT_TIER 业绩阶梯
            //（提点进费率链/金额独立扣款）；GRADE_AMOUNT 店长积分等级扣金额。
            // 旧快照（无 perfScheme 节点）兜底走积分口径，历史批次重算零差异。
            ScoreFactsDTO scoreFacts = input.scoreFacts == null ? null : input.scoreFacts.get(emp.getEmployeeId());
            PerfResult perf = resolvePerf(snap, emp, scoreFacts, newSignPerfConverted);
            d.setPerfGrade(perf.grade);
            BigDecimal perfDeduct = perf.rate;
            d.setPerfDeduct(MoneyUtil.round6(perfDeduct));
            d.setPerfDeductFee(perf.fee == null ? null : MoneyUtil.round2(perf.fee));
            d.setPerfSchemeCode(perf.schemeCode);
            d.setPerfCalcProcess(writeProcessJson(perf.process));

            // 招聘加点（经纪人模式 RATE_ADD）
            int apprenticeCnt = input.qualifiedApprenticeCount.getOrDefault(emp.getEmployeeId(), 0);
            JsonNode rc = rank.path("ruleContent");
            BigDecimal mentorAdd = calcMentorAdd(rc, apprenticeCnt);

            BigDecimal finalRate = baseRate.add(perfDeduct).add(mentorAdd);
            // EMPLOYEE 级提点覆盖（一级一价，覆盖 baseRate + perfDeduct + mentorAdd 全部）
            JsonNode rateOverride = snap.employeeOverride(emp.getEmployeeCode());
            if (rateOverride != null && rateOverride.has("rate")) {
                finalRate = bd(rateOverride.path("rate").asText());
            }
            // 提成点调整（员工业绩扣点）：未参保自动扣点（档案 SOCIAL 事实判断，
            // 规则 policy.noSocialDeduct 配置）+ 审批通过的人工调整单，覆盖后仍叠加
            List<RateAdjustItem> adjustItems = new ArrayList<>(
                input.manualAdjustItems == null
                    ? List.of() : input.manualAdjustItems.getOrDefault(emp.getEmployeeId(), List.of()));
            // 未参保自动扣点单列（结佣逐期间重算时保持算薪月参保状态复用）
            RateAdjustItem noSocialAuto = null;
            if (!Boolean.TRUE.equals(emp.getIsPartTime()) && !Boolean.TRUE.equals(emp.getSocialInsured())) {
                BigDecimal noSocialDeduct = bd(snap.policy().path("noSocialDeduct").asText("0"));
                if (noSocialDeduct.signum() != 0) {
                    noSocialAuto = new RateAdjustItem();
                    noSocialAuto.setType("NO_SOCIAL");
                    noSocialAuto.setRate(noSocialDeduct);
                    noSocialAuto.setReason("未买社保，按规则自动扣点");
                    noSocialAuto.setSource("AUTO");
                    adjustItems.add(noSocialAuto);
                }
            }
            BigDecimal manualAdjust = BigDecimal.ZERO;
            for (RateAdjustItem it : adjustItems) {
                if (it.getRate() != null) {
                    manualAdjust = manualAdjust.add(it.getRate());
                }
            }
            if (manualAdjust.signum() != 0) {
                finalRate = finalRate.add(manualAdjust);
            }
            if (finalRate.compareTo(BigDecimal.ZERO) < 0) {
                finalRate = BigDecimal.ZERO;
            }
            d.setFinalRate(MoneyUtil.round6(finalRate));
            d.setManualAdjust(MoneyUtil.round6(manualAdjust));
            // 所有扣点合计：前端"绩效提成扣点"列统一展示此值（等级 + 未参保自动 + 人工调整）
            d.setTotalDeduct(MoneyUtil.round6(perfDeduct.add(manualAdjust)));
            // 伯乐加点仅 RATE_ADD 模式落库（前端「伯乐奖励」列据此自适应：mentorAdd 非空=加点比例，空=显示 mentorBonus 金额）
            if ("RATE_ADD".equals(rc.path("mentorBonusMode").asText())) {
                d.setMentorAdd(MoneyUtil.round6(mentorAdd));
            }
            d.setRateAdjustJson(adjustItems.isEmpty() ? null : writeAdjustJson(adjustItems));

            // 结佣提成：统一按新签导入月（对应新签业绩事实的归属月）取规则（2026-10-17 改，
            // 取代 2026-09-28 的签约月口径与 2026-10-12 的业绩归属月口径）
            // 不同新签导入月规则（职级提点/绩效扣点）可能不同，需逐期间分别折算+算提成后汇总。
            BigDecimal commissionPerf;
            BigDecimal commissionIncome;
            BigDecimal finalRateForCommission = finalRate; // 经纪人/总监默认用 baseRate 口径

            if (role == EmployeeRole.MANAGER) {
                // 店长用 personalRate 口径
                BigDecimal personalRate = bd(rank.path("personalRate").asText("0.70"));
                finalRateForCommission = personalRate.add(perfDeduct).add(mentorAdd).add(manualAdjust);
                if (finalRateForCommission.compareTo(BigDecimal.ZERO) < 0) {
                    finalRateForCommission = BigDecimal.ZERO;
                }
                d.setFinalRate(MoneyUtil.round6(finalRateForCommission));
            }

            // 结佣明细按规则期间分组计算
            List<CommissionItemDTO> lockedItems = input.lockedByEmp.get(emp.getEmployeeId());
            if (lockedItems != null && !lockedItems.isEmpty()) {
                // 按规则期间分组
                Map<String, List<CommissionItemDTO>> byRulePeriod = new HashMap<>();
                for (CommissionItemDTO it : lockedItems) {
                    String rulePeriod = resolveCommissionRulePeriod(it, input.factPeriodMap);
                    byRulePeriod.computeIfAbsent(rulePeriod, k -> new ArrayList<>()).add(it);
                }
                commissionPerf = BigDecimal.ZERO;
                commissionIncome = BigDecimal.ZERO;
                // 分笔计算过程（期间→业绩/提点/金额），存在跨月回溯时落地到明细供展示
                List<Map<String, Object>> calcProcess = new ArrayList<>();
                // 逐期间分别用对应规则快照算提成
                for (Map.Entry<String, List<CommissionItemDTO>> entry : byRulePeriod.entrySet()) {
                    String rulePeriod = entry.getKey();
                    List<CommissionItemDTO> periodItems = entry.getValue();
                    RuleService.ParsedSnapshot periodSnap = input.periodSnapshots.getOrDefault(rulePeriod, snap);

                    // 职级按新签导入月取历史切片（升职员工的新签月职级 ≠ 算薪月职级；未命中回退算薪月职级）
                    String periodLevel = input.periodLevelMap
                        .getOrDefault(rulePeriod, Map.of())
                        .getOrDefault(emp.getEmployeeId(), level);
                    JsonNode periodRank = periodSnap.rank(periodLevel);

                    // 招聘加点：千里马数按新签月伯乐切片（伯乐号生效日期≤该月），未命中回退算薪月；
                    // 费率/上限取该期间快照
                    int periodApprenticeCnt = input.periodApprenticeMap
                        .getOrDefault(rulePeriod, Map.of())
                        .getOrDefault(emp.getEmployeeId(), apprenticeCnt);
                    BigDecimal periodMentorAdd = calcMentorAdd(periodRank.path("ruleContent"), periodApprenticeCnt);

                    // 绩效等级按新签月积分事实派生；阈值/费率取该期间快照。
                    // 切片已构建该月但无绩效事实 → 0 分（→C 扣点）；整体未构建（回退场景）→ 用算薪月积分
                    ScoreFactsDTO periodScore;
                    if (input.periodScoreMap.containsKey(rulePeriod)) {
                        periodScore = input.periodScoreMap.get(rulePeriod).get(emp.getEmployeeId());
                        if (periodScore == null) {
                            periodScore = new ScoreFactsDTO();
                            periodScore.setTotalPoints(BigDecimal.ZERO);
                            periodScore.setAttendDays(1); // 出勤 1 天使 0/1=0 分走等级派生（否则 0 出勤会兜底 A）
                        }
                    } else {
                        periodScore = scoreFacts;
                    }

                    // 人工调整按新签导入月当时生效的调整单（该月无调整=不叠加，即 0）；
                    // 未参保自动扣点保持算薪月参保状态（当月合规事实）
                    List<RateAdjustItem> periodAdjustItems = new ArrayList<>(
                        input.periodAdjustMap.getOrDefault(rulePeriod, Map.of())
                            .getOrDefault(emp.getEmployeeId(), List.of()));
                    if (noSocialAuto != null) {
                        periodAdjustItems.add(noSocialAuto);
                    }

                    // 用该期间快照折算业绩（先算：RATE_TIER 方案在该期间阶梯上按此定档）
                    BigDecimal periodPerf = applyConversion(periodItems, periodSnap);

                    // 用该期间快照重新计算 finalRate（职级提点/绩效扣点/招聘加点均按新签月口径）
                    BigDecimal periodFinalRate = computeFinalRateForCommission(periodSnap, periodLevel, emp,
                        periodScore, periodAdjustItems, periodRank.path("ruleContent"), periodMentorAdd, role,
                        periodPerf);

                    BigDecimal periodIncome = MoneyUtil.round2(periodPerf.multiply(periodFinalRate));
                    commissionPerf = commissionPerf.add(periodPerf);
                    commissionIncome = commissionIncome.add(periodIncome);
                    // 收集分笔过程：month=新签导入月（无回溯时=算薪月）
                    Map<String, Object> step = new java.util.LinkedHashMap<>();
                    step.put("month", rulePeriod);
                    step.put("perf", periodPerf);
                    step.put("rate", periodFinalRate);
                    step.put("income", periodIncome);
                    calcProcess.add(step);
                }
                // 仅当存在跨月回溯（新签导入月 ≠ 算薪月）时落地计算过程；全部当月结佣保持原样
                // 注意：此处用 input.period（d.period 由批次服务在引擎返回后才赋值，引擎内为 null）
                boolean hasBacktrack = calcProcess.stream()
                    .anyMatch(p -> !input.period.equals(p.get("month")));
                if (hasBacktrack) {
                    try {
                        d.setCommissionCalcProcess(
                            new tools.jackson.databind.ObjectMapper().writeValueAsString(calcProcess));
                    } catch (Exception ignore) {
                        // 序列化失败不影响算薪结果
                    }
                }
            } else {
                commissionPerf = BigDecimal.ZERO;
                commissionIncome = BigDecimal.ZERO;
            }

            d.setCommissionIncome(commissionIncome);
            d.setCommissionPerformance(MoneyUtil.round2(commissionPerf));

            // 落地：当月新签业绩（折算后，主循环上方已算）+ 新签提成比例（baseRate，所有角色通用）
            d.setNewSignPerformance(MoneyUtil.round2(newSignPerfConverted));
            d.setNewSignRate(role == EmployeeRole.MANAGER
                ? bd(rank.path("personalRate").asText("0.70")) : baseRate);

            // 伯乐奖励（AMOUNT_RATIO，店长/总监）＝千里马当月结佣业绩合计（折算后）× 配置比例
            BigDecimal mentorBonus = BigDecimal.ZERO;
            if (role == EmployeeRole.MANAGER || role == EmployeeRole.DIRECTOR) {
                if ("AMOUNT_RATIO".equals(rc.path("mentorBonusMode").asText())) {
                    BigDecimal apprenticeTotal = apprenticePerfByMentor.getOrDefault(emp.getEmployeeId(), BigDecimal.ZERO);
                    BigDecimal ratio = bd(rc.path("mentorBonusAmountRatio").asText("0.02"));
                    mentorBonus = MoneyUtil.round2(apprenticeTotal.multiply(ratio));
                }
            }
            d.setMentorBonus(mentorBonus);

            BigDecimal baseSalary = BigDecimal.ZERO;
            BigDecimal teamIncome = BigDecimal.ZERO;
            BigDecimal personalNewsign = BigDecimal.ZERO;
            BigDecimal storeIncome = BigDecimal.ZERO;
            BigDecimal guaranteeFill = BigDecimal.ZERO;

            if (role == EmployeeRole.MANAGER) {
                // finalRate 已在上方用 personalRate 口径计算，这里只保留非提成的逻辑

                // 团队提成 = (组新签合计 - 店长本人新签 - 组社保业绩扣款 - 新签与结佣差额) × teamRate
                // 口径：团队业绩 = 店长所在组别新签合计，排除店长本人的新签（2026-10-12 改，不再归一到门店）
                // 社保业绩扣款 = 组社保扣减标准 × 组计缴参保人数（非兼职+参保+个人比例>30%）
                // 新签与结佣差额 = 组当月配置值（pj_payroll_dept_monthly_config）
                BigDecimal teamRate = bd(rank.path("teamRate").asText("0.10"));
                Long groupDeptId = emp.getDeptId();
                BigDecimal deptTotal = input.deptNewSignTotal.getOrDefault(groupDeptId, BigDecimal.ZERO);
                // 排除店长本人的新签（newSignPerfConverted 已在上方用同 snap 折算，口径与 deptNewSignTotal 一致）
                deptTotal = deptTotal.subtract(newSignPerfConverted);
                BigDecimal deptSocial = input.deptEmployerSocialTotal == null
                    ? BigDecimal.ZERO : input.deptEmployerSocialTotal.getOrDefault(groupDeptId, BigDecimal.ZERO);
                BigDecimal deptDiff = input.deptDiffTotal == null
                    ? BigDecimal.ZERO : input.deptDiffTotal.getOrDefault(groupDeptId, BigDecimal.ZERO);
                BigDecimal billableBase = deptTotal.subtract(deptSocial).subtract(deptDiff);
                teamIncome = MoneyUtil.round2(billableBase.multiply(teamRate));
                d.setTeamIncome(teamIncome);

                // 个人新签提成 = 个人新签业绩（折算后）× 职级 personalRate → 递延
                BigDecimal personalRateVal = bd(rank.path("personalRate").asText("0.70"));
                BigDecimal personalNewsignPerf = applyConversion(input.newsignByEmp.get(emp.getEmployeeId()), snap);
                personalNewsign = MoneyUtil.round2(personalNewsignPerf.multiply(personalRateVal));
                d.setPersonalNewsignIncome(personalNewsign);
                // 落地：当月新签业绩（折算后）
                d.setNewSignPerformance(MoneyUtil.round2(personalNewsignPerf));

                // 保底补足 = MAX(min, team+ps) - ps - team
                BigDecimal minSalary = bd(rank.path("minSalary").asText("0"));
                BigDecimal sumTp = teamIncome.add(personalNewsign);
                BigDecimal maxOfMin = minSalary.max(sumTp);
                guaranteeFill = MoneyUtil.round2(maxOfMin.subtract(personalNewsign).subtract(teamIncome));
                if (guaranteeFill.compareTo(BigDecimal.ZERO) < 0) {
                    guaranteeFill = BigDecimal.ZERO;
                }
                d.setGuaranteeFill(guaranteeFill);
                // 保底补足计算过程：保底线 vs 团队+个人新签（含构成），未触发也落地供前端展示
                if (minSalary.compareTo(BigDecimal.ZERO) > 0) {
                    List<Map<String, Object>> gpSteps = new ArrayList<>();
                    Map<String, Object> gpTeam = new java.util.LinkedHashMap<>();
                    gpTeam.put("label", "团队提成");
                    gpTeam.put("value", teamIncome);
                    gpSteps.add(gpTeam);
                    Map<String, Object> gpNewsign = new java.util.LinkedHashMap<>();
                    gpNewsign.put("label", "个人新签(递延)");
                    gpNewsign.put("value", personalNewsign);
                    gpSteps.add(gpNewsign);
                    Map<String, Object> gp = new java.util.LinkedHashMap<>();
                    gp.put("minSalary", minSalary);
                    gp.put("compareLabel", "团队提成+个人新签");
                    gp.put("compareValue", sumTp);
                    gp.put("steps", gpSteps);
                    gp.put("fill", guaranteeFill);
                    d.setGuaranteeCalcProcess(writeGuaranteeJson(gp));
                }

                // 落地：店长 sheet 展示字段（组新签/社保标准/参保人数/社保业绩扣款/差额/团队提成比例/保底）
                BigDecimal deptStandard = input.deptSocialStandard == null
                    ? BigDecimal.ZERO : input.deptSocialStandard.getOrDefault(groupDeptId, BigDecimal.ZERO);
                int insuredCount = input.deptInsuredCount == null
                    ? 0 : input.deptInsuredCount.getOrDefault(groupDeptId, 0);
                d.setDeptNewSignTotal(MoneyUtil.round2(deptTotal));
                d.setDeptSocialStandard(MoneyUtil.round2(deptStandard));
                d.setDeptInsuredCount(insuredCount);
                d.setDeptEmployerSocialTotal(MoneyUtil.round2(deptSocial));
                d.setDeptDiffAmount(MoneyUtil.round2(deptDiff));
                d.setTeamRate(teamRate);
                d.setMinSalary(minSalary);

            } else if (role == EmployeeRole.DIRECTOR) {
                baseSalary = bd(rank.path("baseSalary").asText("6000"));
                d.setBaseSalary(MoneyUtil.round2(baseSalary));

                // 总监多组：遍历管辖组别，各组独立按合计跳点算提成，汇总 storeIncome。
                // 对齐天街工资表总监 sheet（每组一行：新签/社保/合计/跳点/提成金额，
                // 提成金额汇总到第一行发工资）。
                // 口径（2026-10-19）：「不计入店长团队」开关只影响店长团队提成，
                // 总监管辖组跳点用原始口径（含被排除员工），同组数字与店长侧允许不同
                Map<Long, BigDecimal> dirNewSign = input.deptNewSignTotalRaw != null
                    ? input.deptNewSignTotalRaw : input.deptNewSignTotal;
                Map<Long, BigDecimal> dirSocialMap = input.deptEmployerSocialTotalRaw != null
                    ? input.deptEmployerSocialTotalRaw : input.deptEmployerSocialTotal;
                List<Long> managedDepts;
                if (input.directorStoreDepts != null && input.directorStoreDepts.containsKey(emp.getEmployeeId())) {
                    managedDepts = input.directorStoreDepts.get(emp.getEmployeeId());
                } else {
                    // 兼容：未传管辖列表时按单门店 emp.getDeptId() 处理（保留旧测试口径）
                    managedDepts = emp.getDeptId() == null ? List.of() : List.of(emp.getDeptId());
                }
                JsonNode brackets = rank.path("ruleContent").path("brackets");
                BigDecimal sumNewSign = BigDecimal.ZERO;
                BigDecimal sumSocial = BigDecimal.ZERO;
                BigDecimal sumDiff = BigDecimal.ZERO;
                BigDecimal sumIncome = BigDecimal.ZERO;
                List<DirectorStoreItem> storeItems = new ArrayList<>();
                for (Long deptId : managedDepts) {
                    BigDecimal deptTotal = dirNewSign.getOrDefault(deptId, BigDecimal.ZERO);
                    BigDecimal deptSocial = dirSocialMap == null
                        ? BigDecimal.ZERO : dirSocialMap.getOrDefault(deptId, BigDecimal.ZERO);
                    BigDecimal deptDiff = input.deptDiffTotal == null
                        ? BigDecimal.ZERO : input.deptDiffTotal.getOrDefault(deptId, BigDecimal.ZERO);
                    if (deptTotal.signum() == 0 && deptSocial.signum() == 0 && deptDiff.signum() == 0) continue;
                    // 合计（计薪业绩）= 新签 - 社保业绩扣款 - 新签与结佣差额
                    BigDecimal billable = deptTotal.subtract(deptSocial).subtract(deptDiff);
                    BigDecimal dirRate = BigDecimal.ZERO;
                    if (brackets.isArray()) {
                        for (JsonNode b : brackets) {
                            BigDecimal min = bd(b.path("min").asText("0"));
                            if (billable.compareTo(min) >= 0) {
                                dirRate = bd(b.path("rate").asText("0"));
                            }
                        }
                    }
                    BigDecimal income = MoneyUtil.round2(billable.multiply(dirRate));
                    sumNewSign = sumNewSign.add(deptTotal);
                    sumSocial = sumSocial.add(deptSocial);
                    sumDiff = sumDiff.add(deptDiff);
                    sumIncome = sumIncome.add(income);
                    String deptName = input.deptNames == null ? null : input.deptNames.get(deptId);
                    storeItems.add(new DirectorStoreItem(deptId, deptName, deptTotal, deptSocial, deptDiff, billable, dirRate, income));
                }
                storeIncome = MoneyUtil.round2(sumIncome);
                d.setStoreIncome(storeIncome);
              //  d.setNewSignPerformance(BigDecimal.ZERO);
             //   d.setNewSignRate(bd(rank.path("personalRate").asText("0")));
                // 落地：汇总值（一行展示）+ JSON 明细（导出按组分行）
                d.setDeptNewSignTotal(MoneyUtil.round2(sumNewSign));
                d.setDeptEmployerSocialTotal(MoneyUtil.round2(sumSocial));
                d.setDeptDiffAmount(MoneyUtil.round2(sumDiff));
                d.setStoreRate(BigDecimal.ZERO);
                d.setFullAttendance(bd(snap.policy().path("fullAttendance").asText("500")));
                d.setDirectorStoreItems(storeItems.isEmpty() ? null : writeStoreItemsJson(storeItems));
            } else {
                // 经纪人底薪：从职级规则快照通用读取（A0 实习期、C0/C1 新人保护期等）
                baseSalary = bd(rank.path("baseSalary").asText("0"));

                // 经纪人保底规则：业绩部分 = 结佣提成 + 保底补足，其中
                // 保底补足 = MAX(保底工资, 个人新签提成) - 个人新签提成
                // 仅当职级配置了保底工资（minSalary > 0）时生效；未配置则经纪人新签业绩不参与薪酬核算
                // 个人新签提成与结佣提成完全同口径（2026-10-18 改）：按新签导入月分期间，
                // 各期间用该月规则快照（职级提点/绩效扣点/调整单）重算 finalRate 后折算+算提成——
                // 同一笔业绩无论按新签口径还是结佣口径，费率一致，保底比较才有意义。
                // 注意：个人新签提成与结佣提成同源（同一笔业绩钱），不单独计入应发，仅作保底比较基准
                BigDecimal minSalary = bd(rank.path("minSalary").asText("0"));
                // 个人新签分笔计算过程（期间→业绩/费率/金额），与结佣提成过程同构
                List<Map<String, Object>> nsSteps = new ArrayList<>();
                if (minSalary.compareTo(BigDecimal.ZERO) > 0) {
                    List<CommissionItemDTO> nsItems = input.newsignByEmp.get(emp.getEmployeeId());
                    if (nsItems != null && !nsItems.isEmpty()) {
                        // 按新签导入月分组（与结佣提成共用 resolveCommissionRulePeriod / factPeriodMap）
                        Map<String, List<CommissionItemDTO>> nsByRulePeriod = new HashMap<>();
                        for (CommissionItemDTO it : nsItems) {
                            String rulePeriod = resolveCommissionRulePeriod(it, input.factPeriodMap);
                            nsByRulePeriod.computeIfAbsent(rulePeriod, k -> new ArrayList<>()).add(it);
                        }
                        // 逐期间分别用对应规则快照算提成
                        for (Map.Entry<String, List<CommissionItemDTO>> entry : nsByRulePeriod.entrySet()) {
                            String rulePeriod = entry.getKey();
                            List<CommissionItemDTO> periodItems = entry.getValue();
                            RuleService.ParsedSnapshot periodSnap = input.periodSnapshots.getOrDefault(rulePeriod, snap);

                            // 职级按新签导入月取历史切片（未命中回退算薪月职级）
                            String periodLevel = input.periodLevelMap
                                .getOrDefault(rulePeriod, Map.of())
                                .getOrDefault(emp.getEmployeeId(), level);
                            JsonNode periodRank = periodSnap.rank(periodLevel);

                            // 绩效等级按新签月积分事实派生（切片已构建该月但无事实 → 0 分走 C 扣点；
                            // 整体未构建（回退场景）→ 用算薪月积分），与结佣提成同口径
                            ScoreFactsDTO periodScore;
                            if (input.periodScoreMap.containsKey(rulePeriod)) {
                                periodScore = input.periodScoreMap.get(rulePeriod).get(emp.getEmployeeId());
                                if (periodScore == null) {
                                    periodScore = new ScoreFactsDTO();
                                    periodScore.setTotalPoints(BigDecimal.ZERO);
                                    periodScore.setAttendDays(1); // 出勤 1 天使 0/1=0 分走等级派生
                                }
                            } else {
                                periodScore = scoreFacts;
                            }

                            // 人工调整按新签导入月当时生效的调整单（该月无调整=不叠加）；
                            // 未参保自动扣点保持算薪月参保状态
                            List<RateAdjustItem> periodAdjustItems = new ArrayList<>(
                                input.periodAdjustMap.getOrDefault(rulePeriod, Map.of())
                                    .getOrDefault(emp.getEmployeeId(), List.of()));
                            if (noSocialAuto != null) {
                                periodAdjustItems.add(noSocialAuto);
                            }

                            // 经纪人无招聘加点（mentorAdd=0），其余口径与结佣提成一致；
                            // 期间业绩先算（RATE_TIER 方案在该期间阶梯上按此定档）
                            BigDecimal periodPerf = applyConversion(periodItems, periodSnap);
                            BigDecimal periodFinalRate = computeFinalRateForCommission(periodSnap, periodLevel, emp,
                                periodScore, periodAdjustItems, periodRank.path("ruleContent"), BigDecimal.ZERO, role,
                                periodPerf);

                            BigDecimal periodIncome = MoneyUtil.round2(periodPerf.multiply(periodFinalRate));
                            personalNewsign = personalNewsign.add(periodIncome);
                            // 收集分笔过程：month=新签导入月
                            Map<String, Object> nsStep = new java.util.LinkedHashMap<>();
                            nsStep.put("month", rulePeriod);
                            nsStep.put("perf", periodPerf);
                            nsStep.put("rate", periodFinalRate);
                            nsStep.put("income", periodIncome);
                            nsSteps.add(nsStep);
                        }
                        personalNewsign = MoneyUtil.round2(personalNewsign);
                    }
                    // 保底补足 = MAX(保底工资, 个人新签提成) - 个人新签提成
                    guaranteeFill = MoneyUtil.round2(minSalary.max(personalNewsign).subtract(personalNewsign));
                    if (guaranteeFill.compareTo(BigDecimal.ZERO) < 0) {
                        guaranteeFill = BigDecimal.ZERO;
                    }
                    d.setPersonalNewsignIncome(personalNewsign);
                    d.setGuaranteeFill(guaranteeFill);
                    d.setMinSalary(minSalary);
                    // 保底补足计算过程：保底线 vs 个人新签提成（含分笔回溯），未触发也落地供前端展示
                    Map<String, Object> gp = new java.util.LinkedHashMap<>();
                    gp.put("minSalary", minSalary);
                    gp.put("compareLabel", "个人新签提成");
                    gp.put("compareValue", personalNewsign);
                    gp.put("newsignSteps", nsSteps);
                    gp.put("fill", guaranteeFill);
                    d.setGuaranteeCalcProcess(writeGuaranteeJson(gp));
                }
            }
            d.setBaseSalary(MoneyUtil.round2(baseSalary));

            // 日工资 = 底薪 / 当月自然天数（2026-10 改：不再用 21.75 工作日口径）。
            // 入职当月：底薪 = 入职日（含）至月底天数 × 日工资；非入职月发满月底薪。
            // dailyWageFull 始终以满月底薪为基（请假/旷工扣款同口径），不受折算影响。
            YearMonth periodMonth = resolvePeriodMonth(input);
            int monthDays = periodMonth.lengthOfMonth();
            BigDecimal dailyWageFull = baseSalary.signum() > 0
                ? baseSalary.divide(BigDecimal.valueOf(monthDays), 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            BigDecimal payableBaseSalary = baseSalary;
            if (baseSalary.signum() > 0 && emp.getHireDate() != null
                && YearMonth.from(emp.getHireDate()).equals(periodMonth)
                && emp.getHireDate().getDayOfMonth() > 1) {
                int employedDays = monthDays - emp.getHireDate().getDayOfMonth() + 1;
                payableBaseSalary = MoneyUtil.round2(dailyWageFull.multiply(BigDecimal.valueOf(employedDays)));
                d.setBaseSalary(payableBaseSalary);
            }

            // 奖金 / 其他收入（手工录入按 itemType 分流，分别落工资明细列）
            BigDecimal bonus = input.bonusIncome == null ? BigDecimal.ZERO
                : input.bonusIncome.getOrDefault(emp.getEmployeeId(), BigDecimal.ZERO);
            BigDecimal otherIncome = input.otherIncomeMap == null ? BigDecimal.ZERO
                : input.otherIncomeMap.getOrDefault(emp.getEmployeeId(), BigDecimal.ZERO);
            d.setBonus(MoneyUtil.round2(bonus));
            d.setOtherIncome(MoneyUtil.round2(otherIncome));

            // 应发 = 结佣提成 + 保底补足 + 团队 + 门店 + 底薪 + 伯乐奖励 + 奖金 + 其他收入 + 全勤奖
            // 注：店长个人新签递延不计入当月应发；经纪人保底场景个人新签提成不单独计入应发
            //（与结佣提成同源同一笔业绩钱，单独计入即重复计薪），仅作为保底比较基准落明细列
            BigDecimal fullAttendance = d.getFullAttendance() == null
                ? BigDecimal.ZERO : d.getFullAttendance();
            BigDecimal gross = commissionIncome.add(teamIncome).add(guaranteeFill)
                .add(storeIncome).add(payableBaseSalary).add(mentorBonus).add(bonus).add(otherIncome)
                .add(fullAttendance);
            // 保底触发时，兜底 gross 至少等于「保底线 − 个人新签递延 + 结佣提成」（避免中间 round2 精度丢失导致 gross 偏低）；
            // 个人新签提成递延、不计入当月应发（见上方组装注释），因此托底基线不能直接用 minSalary，
            // 否则会把递延的个人新签提成重复计入当月应发。
            if (role == EmployeeRole.MANAGER && guaranteeFill.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal minSal = d.getMinSalary() == null ? BigDecimal.ZERO : d.getMinSalary();
                // 保底场景 gross = commissionIncome + teamIncome + guaranteeFill，应等于 (minSalary − 个人新签提成) + commissionIncome
                BigDecimal expectedFloor = minSal.subtract(personalNewsign).add(commissionIncome);
                if (gross.compareTo(expectedFloor) < 0) {
                    gross = expectedFloor;
                }
            }
            d.setGross(MoneyUtil.round2(gross));

            // ==================== 支出项 ====================
            boolean parttime = Boolean.TRUE.equals(emp.getIsPartTime());
            JsonNode policy = snap.policy();

            // 社保
            // 档位与职级无关、按人核定（2026-08 实测：同为 A2 有 0/70%/100%/固定档多种），
            // 优先取员工级自定义金额（SalaryFact SOCIAL_FEE），null 则走三级取值：
            // 职级固定额 socialFixedFee > 职级比例 socialSettlementRatio × baseSocial
            BigDecimal socialFee = BigDecimal.ZERO;
            BigDecimal employerSocial = BigDecimal.ZERO;
            if (!parttime && Boolean.TRUE.equals(emp.getSocialInsured())) {
                if (emp.getSocialFee() != null) {
                    socialFee = emp.getSocialFee();
                    BigDecimal baseSocial = bd(policy.path("baseSocial").asText("1637.15"));
                    employerSocial = MoneyUtil.round2(baseSocial.subtract(socialFee).max(BigDecimal.ZERO));
                } else if (policy.path("socialFixedFee").has(level)) {
                    BigDecimal baseSocial = bd(policy.path("baseSocial").asText("1637.15"));
                    socialFee = bd(policy.path("socialFixedFee").path(level).asText("0"));
                    employerSocial = MoneyUtil.round2(baseSocial.subtract(socialFee).max(BigDecimal.ZERO));
                } else {
                    BigDecimal baseSocial = bd(policy.path("baseSocial").asText("1637.15"));
                    BigDecimal ratio = bd(snap.socialRatio().path(level).asText("0.30"));
                    socialFee = MoneyUtil.round2(baseSocial.multiply(ratio));
                    employerSocial = MoneyUtil.round2(baseSocial.multiply(BigDecimal.ONE.subtract(ratio)));
                }
            }
            d.setSocialFee(socialFee);
            d.setEmployerSocial(employerSocial);

            // 公积金
            BigDecimal housingFund = BigDecimal.ZERO;
            if (!parttime && Boolean.TRUE.equals(emp.getHousingInsured())) {
                // 优先取员工级自定义金额（SalaryFact HOUSING_FUND），null 则回退全局默认
                if (emp.getHousingFund() != null) {
                    housingFund = emp.getHousingFund();
                } else {
                    housingFund = bd(policy.path("housingFund").asText("0"));
                }
            }
            d.setHousingFund(MoneyUtil.round2(housingFund));

            // 考勤扣款（2026-10 规则，按 policy.attendance 配置驱动）：
            //   考勤扣款 = 迟到次数 × lateFee
            //            + 旷工天数 × 旷工日扣款
            //            + 事假天数 ×（personalLeaveFee + 一天日工资，仅底薪员工扣日工资）
            //            + 病假（诊所类证明）天数 ×（有底薪 ? sickClinicWithBaseFee : sickClinicNoBaseFee）
            //            + 病假（二甲医院以上）天数 ×（有底薪 ? sickHospitalWithBaseFee : sickHospitalNoBaseFee）
            //            + 旧版合计请假差额 × leaveFee（仅有合计、无分项的历史数据兜底）
            //            + 导入扣款金额（旧扁平模板 receivableAmount，兼容）
            //   旷工日扣款：有底薪 = 日工资 × absentWithBaseTimes（3 倍）；无底薪 = absentNoBaseFee × 3
            //   日工资 = 满月底薪 / 当月自然天数（dailyWageFull，与入职折算同口径）
            // DTO 只装事实（AttendanceMetricsDTO），扣款金额由本引擎按 policy.attendance 规则计算。
            BigDecimal attendanceFee = BigDecimal.ZERO;
            AttendanceMetricsDTO att = input.attendanceMetrics == null
                ? null : input.attendanceMetrics.get(emp.getEmployeeId());
            if (att != null) {
                JsonNode cfg = policy.path("attendance");
                BigDecimal lateFee = bd(cfg.path("lateFee").asText("20"));
                BigDecimal absentBaseTimes = bd(cfg.path("absentWithBaseTimes").asText("3"));
                BigDecimal absentNoBaseFee = bd(cfg.path("absentNoBaseFee").asText("50"));
                BigDecimal personalLeaveFee = bd(cfg.path("personalLeaveFee").asText("50"));
                boolean personalLeaveDeductWage = cfg.path("personalLeaveDeductDailyWage").asBoolean(true);
                BigDecimal sickClinicWithBaseFee = bd(cfg.path("sickClinicWithBaseFee").asText("30"));
                BigDecimal sickClinicNoBaseFee = bd(cfg.path("sickClinicNoBaseFee").asText("20"));
                BigDecimal sickHospitalWithBaseFee = bd(cfg.path("sickHospitalWithBaseFee").asText("20"));
                BigDecimal sickHospitalNoBaseFee = bd(cfg.path("sickHospitalNoBaseFee").asText("0"));
                BigDecimal leaveFee = bd(cfg.path("leaveFee").asText("50"));
                boolean hasBase = baseSalary.signum() > 0;

                if (att.getImportedFee() != null && att.getImportedFee().signum() > 0) {
                    attendanceFee = attendanceFee.add(att.getImportedFee());
                }
                if (att.getLateCount() != null && lateFee.signum() > 0) {
                    attendanceFee = attendanceFee.add(lateFee.multiply(BigDecimal.valueOf(att.getLateCount())));
                }
                // 旷工：有底薪扣三倍日工资；无底薪扣 50×3（倍数同为 absentWithBaseTimes）
                if (att.getAbsentDays() != null && att.getAbsentDays().signum() > 0) {
                    BigDecimal perDay = hasBase
                        ? dailyWageFull.multiply(absentBaseTimes)
                        : absentNoBaseFee.multiply(absentBaseTimes);
                    attendanceFee = attendanceFee.add(att.getAbsentDays().multiply(perDay));
                }
                BigDecimal personalDays = nz(att.getPersonalLeaveDays());
                BigDecimal sickClinicDays = nz(att.getSickClinicDays());
                BigDecimal sickHospitalDays = nz(att.getSickHospitalDays());
                // 事假：固定扣款 personalLeaveFee（50/天）+ 一天底薪（有底薪且规则开启时）
                if (personalDays.signum() > 0) {
                    BigDecimal perDay = personalLeaveFee;
                    if (hasBase && personalLeaveDeductWage) {
                        perDay = perDay.add(dailyWageFull);
                    }
                    attendanceFee = attendanceFee.add(personalDays.multiply(perDay));
                }
                // 病假（诊所类证明）：有底薪 30/天，无底薪 20/天
                if (sickClinicDays.signum() > 0) {
                    BigDecimal perDay = hasBase ? sickClinicWithBaseFee : sickClinicNoBaseFee;
                    attendanceFee = attendanceFee.add(sickClinicDays.multiply(perDay));
                }
                // 病假（二甲医院以上）：有底薪 20/天，无底薪不扣
                if (sickHospitalDays.signum() > 0) {
                    BigDecimal perDay = hasBase ? sickHospitalWithBaseFee : sickHospitalNoBaseFee;
                    attendanceFee = attendanceFee.add(sickHospitalDays.multiply(perDay));
                }
                // 旧版合计兜底：合计天数扣除已按三类规则计算的天数后，差额按 leaveFee 扣款
                // （V210 之前导入/历史数据只有 leaveDays；新数据 leaveDays=三项合计，差额为 0）
                BigDecimal splitDays = personalDays.add(sickClinicDays).add(sickHospitalDays);
                BigDecimal legacyLeaveDays = nz(att.getLeaveDays()).subtract(splitDays).max(BigDecimal.ZERO);
                if (legacyLeaveDays.signum() > 0 && leaveFee.signum() > 0) {
                    attendanceFee = attendanceFee.add(legacyLeaveDays.multiply(leaveFee));
                }
            }
            d.setAttendanceFee(MoneyUtil.round2(attendanceFee));

            // 商业保险
            // 优先取员工级自定义金额（SalaryFact COMMERCIAL_FEE），null 则取全局默认 21 元
            BigDecimal commercialInsurance = BigDecimal.ZERO;
            if (Boolean.TRUE.equals(emp.getCommercialInsured())) {
                if (emp.getCommercialFee() != null) {
                    commercialInsurance = emp.getCommercialFee();
                } else {
                    commercialInsurance = bd(policy.path("commercialInsurance").asText("21"));
                }
            }
            d.setCommercialInsurance(MoneyUtil.round2(commercialInsurance));

            // 宿舍管理费
            // 优先取员工级自定义金额（SalaryFact DORMITORY_FEE），null 则回退全局默认；
            // 仅当员工「住宿舍」开关开启时才扣。
            BigDecimal dormitoryFee = BigDecimal.ZERO;
            if (Boolean.TRUE.equals(emp.getDormitory())) {
                if (emp.getDormitoryFee() != null) {
                    dormitoryFee = emp.getDormitoryFee();
                } else {
                    dormitoryFee = bd(policy.path("dormitoryFee").asText("0"));
                }
            }
            d.setDormitoryFee(MoneyUtil.round2(dormitoryFee));

            // 负工资结转
            BigDecimal negativeCarryover = input.negativeBalance.getOrDefault(emp.getEmployeeId(), BigDecimal.ZERO);
            d.setNegativeCarryover(MoneyUtil.round2(negativeCarryover));

            // 积分扣款（积分日报晚提交处罚：晚提交次数 × 罚款单价）——单价优先取默认积分方案
            // ruleContent.penaltyFee（resolvePerf 装载），缺失回退 policy.points.penaltyFee。
            BigDecimal pointsFee = resolvePointsFee(snap, perf.lateFeeUnit, scoreFacts);
            d.setPointsFee(MoneyUtil.round2(pointsFee));

            // 绩效方案金额扣款（业绩阶梯B版/店长积分绩效：未达标扣固定金额，perf.fee 正数=扣款；
            // perf_deduct_fee 已在主循环绩效方案处落库，此处并入应发扣减，不进保底基数/费率链）
            BigDecimal perfFee = perf.fee == null ? BigDecimal.ZERO : perf.fee;

            // 其他支出
            BigDecimal otherDeduct = input.manualDeduct.getOrDefault(emp.getEmployeeId(), BigDecimal.ZERO);
            d.setOtherDeduct(MoneyUtil.round2(otherDeduct));

            // 支出合计
            BigDecimal deduct = socialFee.add(housingFund).add(attendanceFee)
                .add(commercialInsurance).add(dormitoryFee).add(negativeCarryover)
                .add(pointsFee).add(perfFee).add(otherDeduct);
            d.setDeduct(MoneyUtil.round2(deduct));

            // 个税（累计预扣；EXTERNAL 模式置 0，待外部个税系统算税后导入回补）
            // 缺省 EXTERNAL：系统内个税为简化试算不作为发放依据，需内部试算须在政策里显式配 mode=ENGINE
            BigDecimal netBeforeTax = gross.subtract(deduct);
            boolean externalTax = "EXTERNAL".equalsIgnoreCase(snap.tax().path("mode").asText("EXTERNAL"));
            BigDecimal tax = externalTax ? BigDecimal.ZERO : calcTax(emp, netBeforeTax, input, snap);
            d.setTax(MoneyUtil.round2(tax));
            d.setTaxSource(externalTax ? "EXTERNAL" : "ENGINE");

            // 最终发放
            BigDecimal net = netBeforeTax.subtract(tax);
            d.setNet(MoneyUtil.round2(net));

            details.add(d);
        }
        return details;
    }

    // ==================== 辅助方法 ====================

    private static final tools.jackson.databind.json.JsonMapper ADJUST_JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    /** 提成点调整命中项 → 溯源 JSON（rate_adjust_json 列） */
    private String writeAdjustJson(List<RateAdjustItem> items) {
        try {
            return ADJUST_JSON.writeValueAsString(items);
        } catch (Exception e) {
            log.warn("提成点调整溯源JSON序列化失败", e);
            return null;
        }
    }

    private String writeStoreItemsJson(List<DirectorStoreItem> items) {
        try {
            return ADJUST_JSON.writeValueAsString(items);
        } catch (Exception e) {
            log.warn("总监门店提成明细JSON序列化失败", e);
            return null;
        }
    }

    /** 保底补足计算过程 → 溯源 JSON（guarantee_calc_process 列） */
    private String writeGuaranteeJson(Object process) {
        try {
            return ADJUST_JSON.writeValueAsString(process);
        } catch (Exception e) {
            log.warn("保底补足计算过程JSON序列化失败", e);
            return null;
        }
    }

    /** 绩效方案计算过程 → 溯源 JSON（perf_calc_process 列，未触发扣减也落地供前端展示口径） */
    private String writeProcessJson(Map<String, Object> process) {
        try {
            return ADJUST_JSON.writeValueAsString(process);
        } catch (Exception e) {
            log.warn("绩效方案计算过程JSON序列化失败", e);
            return null;
        }
    }

    /** 总监各门店提成明细（引擎内部类型，序列化为 PayrollDetail.directorStoreItems JSON） */
    public static class DirectorStoreItem {
        public Long deptId;
        public String deptName;
        public BigDecimal newSign;
        public BigDecimal social;
        /** 新签与结佣差额（门店当月配置） */
        public BigDecimal diff;
        public BigDecimal billable;
        public BigDecimal rate;
        public BigDecimal income;
        public DirectorStoreItem() {}
        public DirectorStoreItem(Long deptId, String deptName, BigDecimal newSign, BigDecimal social,
                                 BigDecimal diff, BigDecimal billable, BigDecimal rate, BigDecimal income) {
            this.deptId = deptId;
            this.deptName = deptName;
            this.newSign = newSign;
            this.social = social;
            this.diff = diff;
            this.billable = billable;
            this.rate = rate;
            this.income = income;
        }
    }

    /**
     * 组计缴参保人数判定：非兼职 + 已参保 + 个人社保缴纳比例 > 30%。
     * <p>个人金额取值与个人社保扣款一致：员工核定 socialFee &gt; 职级固定额 socialFixedFee
     * &gt; 职级比例 socialSettlementRatio × baseSocial；A0=30% 不计入，C/S 系比例 0 不计入。
     */
    static boolean isInsuredHeadcount(EmployeeSnapshot emp, RuleService.ParsedSnapshot snap) {
        if (Boolean.TRUE.equals(emp.getIsPartTime()) || !Boolean.TRUE.equals(emp.getSocialInsured())) {
            return false;
        }
        String level = emp.getLevelCode() == null ? "A0" : emp.getLevelCode();
        BigDecimal baseSocial = bd(snap.policy().path("baseSocial").asText("1637.15"));
        BigDecimal personalFee;
        if (emp.getSocialFee() != null) {
            personalFee = emp.getSocialFee();
        } else if (snap.policy().path("socialFixedFee").has(level)) {
            personalFee = bd(snap.policy().path("socialFixedFee").path(level).asText("0"));
        } else {
            personalFee = baseSocial.multiply(bd(snap.socialRatio().path(level).asText("0.30")));
        }
        // 严格 > 30%：personalFee/baseSocial > 0.30（交叉相乘避免除零，baseSocial 为 0 时不计入）
        return baseSocial.signum() > 0
            && personalFee.multiply(HUNDRED).compareTo(baseSocial.multiply(THIRTY)) > 0;
    }

    private EmployeeRole resolveRole(EmployeeSnapshot emp) {
        String pos = emp.getPosition();
        if (pos != null) {
            if (pos.contains("总监")) return EmployeeRole.DIRECTOR;
            if (pos.contains("店长")) return EmployeeRole.MANAGER;
        }
        String level = emp.getLevelCode();
        if (level != null) {
            if (level.startsWith("S")) return EmployeeRole.MANAGER;
            if ("D".equals(level)) return EmployeeRole.DIRECTOR;
        }
        return EmployeeRole.AGENT;
    }

    /**
     * 招聘加点（经纪人模式 RATE_ADD）：费率 × 达标千里马数，封顶 mentorBonusRateCap。
     * 主循环与结佣逐期间重算共用（费率/上限取对应期间职级规则的 ruleContent）。
     */
    private BigDecimal calcMentorAdd(JsonNode ruleContent, int apprenticeCnt) {
        if (!"RATE_ADD".equals(ruleContent.path("mentorBonusMode").asText())) {
            return BigDecimal.ZERO;
        }
        BigDecimal addRate = bd(ruleContent.path("mentorBonusRateAdd").asText("0.02"));
        BigDecimal mentorAdd = addRate.multiply(BigDecimal.valueOf(apprenticeCnt));
        BigDecimal cap = bd(ruleContent.path("mentorBonusRateCap").asText("0.10"));
        return mentorAdd.compareTo(cap) > 0 ? cap : mentorAdd;
    }

    // ==================== 绩效方案（2026-10 方案化） ====================

    /**
     * 绩效方案计算结果：
     * rate=提点扣减（负=扣点，进 finalRate 费率链）；fee=金额扣款（正=扣款，落 perf_deduct_fee
     * 并入应发扣减）；grade 仅积分类方案有；lateFeeUnit=晚提交罚款单价（GRADE_RATE 方案自带）；
     * process=perf_calc_process 溯源（未触发扣减也落地供前端展示口径）。
     */
    private static class PerfResult {
        String schemeCode;
        String schemeName;
        String schemeType;
        String grade;
        BigDecimal rate = BigDecimal.ZERO;
        BigDecimal fee;
        BigDecimal lateFeeUnit;
        Map<String, Object> process = new java.util.LinkedHashMap<>();
    }

    /**
     * 解析员工绩效方案并计算（主循环口径）。
     * <p>
     * 方案来源：员工 PERF_SCHEME 事实（快照 perfSchemeCode）→ 指派方案；
     * 未指派/编码无效 → 默认方案（积分绩效）；旧快照（无 perfScheme 节点，历史批次重算）
     * → 兜底走 policy.points 积分口径，行为与方案化前完全一致。
     * 阶梯定档业绩 perfBase = 算薪月个人新签业绩（折算后）。
     */
    private PerfResult resolvePerf(RuleService.ParsedSnapshot snap, EmployeeSnapshot emp,
                                   ScoreFactsDTO facts, BigDecimal perfBase) {
        JsonNode scheme = snap.resolvePerfScheme(emp.getPerfSchemeCode());
        boolean legacy = scheme.isMissingNode();
        String type = legacy ? "GRADE_RATE" : scheme.path("schemeType").asText("GRADE_RATE");
        JsonNode rc = legacy ? snap.policy().path("points") : scheme.path("ruleContent");

        PerfResult r = new PerfResult();
        if (legacy) {
            r.schemeType = "GRADE_RATE";
        } else {
            // 编码取指派值（快照命中时）否则默认方案编码；快照方案节点未冗余 code 字段
            String assigned = emp.getPerfSchemeCode();
            r.schemeCode = (assigned != null && !assigned.isBlank()
                && !snap.perfScheme(assigned).isMissingNode())
                ? assigned
                : snap.defaultPerfSchemeCode();
            r.schemeName = scheme.path("name").asText(null);
            r.schemeType = type;
        }
        r.process.put("schemeCode", r.schemeCode);
        r.process.put("schemeName", r.schemeName);
        r.process.put("schemeType", r.schemeType);

        switch (type) {
            case "NONE" -> {
                // 无绩效：不考核，无等级无扣减
            }
            case "RATE_TIER" -> {
                // 业绩阶梯扣提点：[min,max) 半开区间定档，rate 负=扣点（进费率链）
                BigDecimal[] tier = hitTier(rc.path("tiers"), perfBase, "rate");
                r.rate = tier[2];
                r.process.put("perf", perfBase == null ? BigDecimal.ZERO : perfBase);
                r.process.put("tierMin", tier[0]);
                r.process.put("tierMax", tier[1]);
                r.process.put("deduct", r.rate);
            }
            case "AMOUNT_TIER" -> {
                // 业绩阶梯扣金额：amount 正=扣款（独立扣款列）
                BigDecimal[] tier = hitTier(rc.path("tiers"), perfBase, "amount");
                r.fee = tier[2];
                r.process.put("perf", perfBase == null ? BigDecimal.ZERO : perfBase);
                r.process.put("tierMin", tier[0]);
                r.process.put("tierMax", tier[1]);
                r.process.put("fee", r.fee);
            }
            case "GRADE_AMOUNT" -> {
                // 积分等级扣金额（店长积分绩效）：A=feeA/B=feeB/C=feeC
                r.grade = resolveGrade(rc, facts);
                r.fee = bd(rc.path("fee" + r.grade).asText("0"));
                r.process.put("grade", r.grade);
                r.process.put("fee", r.fee);
            }
            default -> {
                // GRADE_RATE 积分绩效（历史口径）：等级派生 + 等级扣点 + 晚提交罚款单价
                r.grade = resolveGrade(rc, facts);
                r.rate = perfDeductOf(rc, r.grade);
                JsonNode pf = rc.path("penaltyFee");
                r.lateFeeUnit = pf.isMissingNode() || pf.isNull() ? null : bd(pf.asText("5"));
                r.process.put("grade", r.grade);
                r.process.put("deduct", r.rate);
            }
        }
        return r;
    }

    /**
     * 费率链中的绩效提点扣减（结佣回溯/保底逐期间场景）。
     * NONE→0；GRADE_RATE（含旧快照兜底）→ 该期间积分派生等级扣点（历史行为不变）；
     * RATE_TIER→ 该期间业绩 perfBase 在该期间方案阶梯上定档；金额类方案→0（不进费率链）。
     */
    private BigDecimal perfRateDeductOf(RuleService.ParsedSnapshot snap, EmployeeSnapshot emp,
                                        ScoreFactsDTO facts, BigDecimal perfBase) {
        JsonNode scheme = snap.resolvePerfScheme(emp.getPerfSchemeCode());
        boolean legacy = scheme.isMissingNode();
        String type = legacy ? "GRADE_RATE" : scheme.path("schemeType").asText("GRADE_RATE");
        JsonNode rc = legacy ? snap.policy().path("points") : scheme.path("ruleContent");
        return switch (type) {
            case "NONE" -> BigDecimal.ZERO;
            case "RATE_TIER" -> hitTier(rc.path("tiers"), perfBase, "rate")[2];
            case "AMOUNT_TIER", "GRADE_AMOUNT" -> BigDecimal.ZERO;
            default -> perfDeductOf(rc, resolveGrade(rc, facts));
        };
    }

    /**
     * 阶梯命中：[min,max) 半开区间（max=null 表无穷）；业绩低于首档 min 或未配置阶梯时返回 0。
     *
     * @return [min, max, value]（未命中 min/max 为 null、value 为 0）
     */
    private BigDecimal[] hitTier(JsonNode tiers, BigDecimal perf, String valueKey) {
        BigDecimal base = perf == null ? BigDecimal.ZERO : perf;
        if (tiers.isArray()) {
            for (JsonNode t : tiers) {
                BigDecimal min = t.path("min").isMissingNode() || t.path("min").isNull()
                    ? null : bd(t.path("min").asText("0"));
                BigDecimal max = t.path("max").isMissingNode() || t.path("max").isNull()
                    ? null : bd(t.path("max").asText("0"));
                boolean geMin = min == null || base.compareTo(min) >= 0;
                boolean ltMax = max == null || base.compareTo(max) < 0;
                if (geMin && ltMax) {
                    return new BigDecimal[]{min, max, bd(t.path(valueKey).asText("0"))};
                }
            }
        }
        return new BigDecimal[]{null, null, BigDecimal.ZERO};
    }

    /** 积分等级扣点（deductA/deductB/deductC，A 默认 0 不扣；历史 policy.points 与方案 ruleContent 同构） */
    private BigDecimal perfDeductOf(JsonNode thresholds, String grade) {
        return bd(thresholds.path("deduct" + grade).asText("0"));
    }

    /**
     * 派生绩效等级（事实 → 等级）。
     * <p>
     * 平均积分 = totalPoints / attendDays（HALF_UP 保留 4 位）；
     * 平均分 ≥ gradeA 阈值 → A；≥ gradeB 阈值 → B；否则 C；
     * <b>无积分事实、总积分为空或出勤 0 天 → 方案最差档</b>（堵住"当天积分低就不填报"
     * 来抬高平均分、或整月不填报规避扣点的漏洞）。阈值来源=绩效方案 ruleContent（GRADE_RATE/GRADE_AMOUNT）
     * 或旧快照兜底的 policy.points；字段缺失时各阈值兜底默认 8/6（与
     * 员工域 ScoreGradePolicy 一致）。
     */
    private String resolveGrade(JsonNode thresholds, ScoreFactsDTO facts) {
        if (facts == null) {
            return worstGrade(thresholds);
        }
        BigDecimal totalPoints = facts.getTotalPoints();
        Integer attendDays = facts.getAttendDays();
        if (totalPoints == null || attendDays == null || attendDays <= 0) {
            return worstGrade(thresholds);
        }
        BigDecimal avg = totalPoints.divide(BigDecimal.valueOf(attendDays), 4, RoundingMode.HALF_UP);
        BigDecimal gradeAMin = bd(thresholds.path("gradeA").asText("8"));
        BigDecimal gradeBMin = bd(thresholds.path("gradeB").asText("6"));
        if (avg.compareTo(gradeAMin) >= 0) {
            return "A";
        }
        return avg.compareTo(gradeBMin) >= 0 ? "B" : "C";
    }

    /**
     * 方案最差档：取规则里扣减力度最大的等级，不硬编码等级名。
     * <p>
     * 提点类（deductA/B/C，负值）取最小值；金额类（feeA/B/C，正值扣款）取最大值
     * （统一换算成"负向分值"后比较）。规则里既无 deduct 也无 fee 时兜底 C。
     */
    private String worstGrade(JsonNode thresholds) {
        String worst = null;
        BigDecimal worstScore = null;
        for (String grade : new String[]{"A", "B", "C"}) {
            BigDecimal score = null;
            if (thresholds.hasNonNull("deduct" + grade)) {
                score = bd(thresholds.path("deduct" + grade).asText("0"));
            } else if (thresholds.hasNonNull("fee" + grade)) {
                score = bd(thresholds.path("fee" + grade).asText("0")).negate();
            }
            if (score == null) {
                continue;
            }
            if (worstScore == null || score.compareTo(worstScore) < 0) {
                worstScore = score;
                worst = grade;
            }
        }
        return worst == null ? "C" : worst;
    }

    /**
     * 派生积分扣款（事实 → 金额）。
     * <p>
     * 扣款 = 晚提交次数 × 罚款单价（保留 2 位）；单价优先取默认积分方案 penaltyFee
     * （resolvePerf 装载），缺失回退 policy.points（兜底默认 5，与员工域
     * ScoreGradePolicy.lateFeeOf 一致）；无事实/字段缺失时为 0。
     */
    private BigDecimal resolvePointsFee(RuleService.ParsedSnapshot snap, BigDecimal lateFeeUnit,
                                        ScoreFactsDTO facts) {
        if (facts == null || facts.getLateSubmitCount() == null || facts.getLateSubmitCount() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal penaltyFee = lateFeeUnit != null
            ? lateFeeUnit
            : bd(snap.policy().path("points").path("penaltyFee").asText("5"));
        return penaltyFee.multiply(BigDecimal.valueOf(facts.getLateSubmitCount()))
            .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 解析结佣明细对应的规则期间。
     * <p>
     * 口径：按<b>新签导入月</b>（该笔结佣对应新签业绩事实的归属月）取规则快照（2026-10-17 改）。
     * 映射由 PayrollBatchService 预先构建：绑新签事实（PERF_EXPECT）取其 period；
     * 绑实收明细（PERF_REAL）按业务键+员工匹配新签事实取最早归属月，未匹配时兜底结佣导入月。
     * factId 未命中映射时返回空串（回退主快照）。
     *
     * @param item 结佣明细
     * @param factPeriodMap factId → 新签导入月（由 PayrollBatchService 预先构建）
     * @return 规则期间（YYYY-MM）
     */
    private String resolveCommissionRulePeriod(CommissionItemDTO item, Map<Long, String> factPeriodMap) {
        if (item.getPerformanceFactId() != null && factPeriodMap != null) {
            return factPeriodMap.getOrDefault(item.getPerformanceFactId(), "");
        }
        return "";
    }

    /**
     * 用指定规则快照计算结佣提成的 finalRate。
     * <p>
     * 与 calculate 主循环中对主快照算 finalRate 逻辑完全一致，但：
     * <ul>
     *   <li>入参传入指定的 ParsedSnapshot（新签导入月对应的规则快照）；</li>
     *   <li>店长角色用 personalRate 口径，其他角色用 baseRate 口径；</li>
     *   <li>用于按规则期间分组计算结佣提成时逐期间调用。</li>
     * </ul>
     */
    private BigDecimal computeFinalRateForCommission(RuleService.ParsedSnapshot snap, String level,
                                                     EmployeeSnapshot emp, ScoreFactsDTO scoreFacts,
                                                     List<RateAdjustItem> adjustItems, JsonNode rc,
                                                     BigDecimal mentorAdd, EmployeeRole role,
                                                     BigDecimal perfBase) {
        JsonNode rank = snap.rank(level);

        // 基础提点：店长用 personalRate（70%），其他角色用 baseRate
        BigDecimal baseRate;
        if (role == EmployeeRole.MANAGER) {
            baseRate = bd(rank.path("personalRate").asText("0.70"));
        } else {
            baseRate = bd(rank.path("baseRate").asText("0"));
        }

        // 绩效扣点（用该期间快照的绩效方案：积分类按该期间积分派生；阶梯类按该期间业绩定档；
        // 金额类不进费率链）
        BigDecimal perfDeduct = perfRateDeductOf(snap, emp, scoreFacts, perfBase);

        BigDecimal finalRate = baseRate.add(perfDeduct).add(mentorAdd);

        // EMPLOYEE 级提点覆盖
        JsonNode rateOverride = snap.employeeOverride(emp.getEmployeeCode());
        if (rateOverride != null && rateOverride.has("rate")) {
            finalRate = bd(rateOverride.path("rate").asText());
        }

        // 未参保自动扣点：主循环构造 adjustItems 时已加入 NO_SOCIAL 自动项，
        // 随 manualAdjust 累加即已生效，此处不可重复叠加（否则未参保扣点被扣两次）

        // 人工调整（含未参保自动项）
        BigDecimal manualAdjust = BigDecimal.ZERO;
        for (RateAdjustItem it : adjustItems) {
            if (it.getRate() != null) {
                manualAdjust = manualAdjust.add(it.getRate());
            }
        }
        finalRate = finalRate.add(manualAdjust);

        if (finalRate.compareTo(BigDecimal.ZERO) < 0) {
            finalRate = BigDecimal.ZERO;
        }
        return finalRate;
    }

    private BigDecimal calcTax(EmployeeSnapshot emp, BigDecimal netBeforeTax, CalcInput input, RuleService.ParsedSnapshot snap) {
        JsonNode tax = snap.tax();
        BigDecimal threshold = bd(tax.path("threshold").asText("5000"));
        boolean deductSocial = tax.path("deductSocial").asBoolean(true);

        // 应纳税所得额 = 净收入 - 起征点（简化：单月计算，不做累计以避免历史依赖问题）
        // 若有累计数据则用累计预扣
        BigDecimal taxableBase = netBeforeTax;
        if (deductSocial) {
            // netBeforeTax 已扣社保（deduct 含 socialFee），此处不再重复扣
        }

        int months = input.monthsEmployed.getOrDefault(emp.getEmployeeId(), 1);
        BigDecimal cumulativeThreshold = threshold.multiply(BigDecimal.valueOf(months));

        BigDecimal prevTaxable = input.cumulativeTaxable.getOrDefault(emp.getEmployeeId(), BigDecimal.ZERO);
        BigDecimal cumulativeTaxable = prevTaxable.add(taxableBase).subtract(cumulativeThreshold);
        if (cumulativeTaxable.compareTo(BigDecimal.ZERO) < 0) {
            cumulativeTaxable = BigDecimal.ZERO;
        }

        JsonNode brackets = tax.path("brackets");
        BigDecimal rate = BigDecimal.ZERO;
        BigDecimal quick = BigDecimal.ZERO;
        if (brackets.isArray()) {
            for (JsonNode b : brackets) {
                BigDecimal min = bd(b.path("min").asText("0"));
                if (cumulativeTaxable.compareTo(min) >= 0) {
                    rate = bd(b.path("rate").asText("0"));
                    quick = bd(b.path("quick").asText("0"));
                }
            }
        }
        BigDecimal cumulativeTaxAmount = cumulativeTaxable.multiply(rate).subtract(quick);
        BigDecimal prevTax = input.cumulativeTax.getOrDefault(emp.getEmployeeId(), BigDecimal.ZERO);
        BigDecimal monthlyTax = cumulativeTaxAmount.subtract(prevTax);
        if (monthlyTax.compareTo(BigDecimal.ZERO) < 0) {
            monthlyTax = BigDecimal.ZERO;
        }
        return monthlyTax;
    }

    /**
     * 按每条明细的 bizType 应用快照中的折算因子，汇总折算后金额。
     * <p>
     * 新签业绩与结佣业绩均调用本方法折算（一手房 ×0.9024、其他 ×0.96），
     * 口径统一：提成与落地业绩都基于折算后金额。
     * 契约测试 {@link SalaryCalculationEngineTest#testConversionAppliesToAll} 断言此口径。
     */
    private BigDecimal applyConversion(List<CommissionItemDTO> items, RuleService.ParsedSnapshot snapshot) {
        if (items == null) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (CommissionItemDTO it : items) {
            if (it.getAmount() == null) continue;
            BigDecimal factor = snapshot.conversionFactor(it.getBizType());
            sum = sum.add(it.getAmount().multiply(factor));
        }
        return sum;
    }

    private static BigDecimal bd(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        return new BigDecimal(s);
    }

    /** null 安全的 BigDecimal 归零 */
    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 解析算薪归属期间（YYYY-MM）为年月；缺失/非法时回退当前月（不阻断算薪）。
     */
    private YearMonth resolvePeriodMonth(CalcInput input) {
        if (input.period != null && !input.period.isBlank()) {
            try {
                return YearMonth.parse(input.period.trim());
            } catch (Exception e) {
                log.warn("[算薪] 归属期间 {} 非法，日工资/入职折算回退当前月", input.period);
            }
        }
        return YearMonth.now();
    }

}
