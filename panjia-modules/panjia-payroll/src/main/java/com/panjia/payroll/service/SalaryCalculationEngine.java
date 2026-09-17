package com.panjia.payroll.service;

import tools.jackson.databind.JsonNode;
import com.panjia.contracts.dto.CommissionItemDTO;
import com.panjia.contracts.snapshot.EmployeeSnapshot;
import com.panjia.payroll.domain.EmployeeRole;
import com.panjia.payroll.domain.PayrollDetail;
import com.panjia.payroll.util.MoneyUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /**
     * 算薪输入。
     */
    public static class CalcInput {
        public List<EmployeeSnapshot> employees;
        /** employeeId -> 已审批结佣明细（PERF_REAL，不折算） */
        public Map<Long, List<CommissionItemDTO>> lockedByEmp;
        /** employeeId -> 新签业绩明细（PERF_EXPECT，折算） */
        public Map<Long, List<CommissionItemDTO>> newsignByEmp;
        /** deptId -> 门店新签计薪业绩合计（已折算） */
        public Map<Long, BigDecimal> deptNewSignTotal;
        /** 规则快照 */
        public RuleService.ParsedSnapshot snapshot;
        /** employeeId -> 手工收入（奖金+其他收入） */
        public Map<Long, BigDecimal> manualIncome;
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
        /** employeeId -> 考勤扣款 */
        public Map<Long, BigDecimal> attendanceFee;
        /** employeeId -> 积分扣款 */
        public Map<Long, BigDecimal> pointsFee;
        /** employeeId -> 绩效等级 A/B/C */
        public Map<Long, String> perfGrade;
        /** employeeId -> 合格徒弟数（招聘奖励加点用） */
        public Map<Long, Integer> qualifiedApprenticeCount;
        /** employeeId -> 徒弟结佣合计（店长/总监招聘奖励用） */
        public Map<Long, BigDecimal> apprenticeCommission;
    }

    /**
     * 执行算薪，返回工资明细列表（内存对象，未落库）。
     */
    public List<PayrollDetail> calculate(CalcInput input) {
        List<PayrollDetail> details = new ArrayList<>();
        RuleService.ParsedSnapshot snap = input.snapshot;

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

            // 基础提点
            BigDecimal baseRate = bd(rank.path("baseRate").asText("0"));

            // 绩效扣点
            String grade = input.perfGrade.getOrDefault(emp.getEmployeeId(), "A");
            d.setPerfGrade(grade);
            BigDecimal perfDeduct = resolvePerfDeduct(snap, grade);

            // 招聘加点（经纪人模式 RATE_ADD）
            int apprenticeCnt = input.qualifiedApprenticeCount.getOrDefault(emp.getEmployeeId(), 0);
            BigDecimal mentorAdd = BigDecimal.ZERO;
            JsonNode rc = rank.path("ruleContent");
            if ("RATE_ADD".equals(rc.path("mentorBonusMode").asText())) {
                BigDecimal addRate = bd(rc.path("mentorBonusRateAdd").asText("0.02"));
                mentorAdd = addRate.multiply(BigDecimal.valueOf(apprenticeCnt));
                BigDecimal cap = bd(rc.path("mentorBonusRateCap").asText("0.10"));
                if (mentorAdd.compareTo(cap) > 0) {
                    mentorAdd = cap;
                }
            }

            BigDecimal finalRate = baseRate.add(perfDeduct).add(mentorAdd);
            // EMPLOYEE 级提点覆盖（一级一价，覆盖 baseRate + perfDeduct + mentorAdd 全部）
            JsonNode rateOverride = snap.employeeOverride(emp.getEmployeeCode());
            if (rateOverride != null && rateOverride.has("rate")) {
                finalRate = bd(rateOverride.path("rate").asText());
            }
            if (finalRate.compareTo(BigDecimal.ZERO) < 0) {
                finalRate = BigDecimal.ZERO;
            }
            d.setFinalRate(MoneyUtil.round6(finalRate));

            // 结佣业绩（不折算）
            BigDecimal commissionPerf = sumAmount(input.lockedByEmp.get(emp.getEmployeeId()));
            BigDecimal commissionIncome = MoneyUtil.round2(commissionPerf.multiply(finalRate));
            d.setCommissionIncome(commissionIncome);

            // 招聘奖励（店长/总监 = 徒弟结佣 × 2%）
            BigDecimal mentorBonus = BigDecimal.ZERO;
            if (role == EmployeeRole.MANAGER || role == EmployeeRole.DIRECTOR) {
                if ("AMOUNT_RATIO".equals(rc.path("mentorBonusMode").asText())) {
                    BigDecimal ratio = bd(rc.path("mentorBonusAmountRatio").asText("0.02"));
                    mentorBonus = MoneyUtil.round2(input.apprenticeCommission.getOrDefault(emp.getEmployeeId(), BigDecimal.ZERO).multiply(ratio));
                }
            }
            d.setMentorBonus(mentorBonus);

            BigDecimal baseSalary = BigDecimal.ZERO;
            BigDecimal teamIncome = BigDecimal.ZERO;
            BigDecimal personalNewsign = BigDecimal.ZERO;
            BigDecimal storeIncome = BigDecimal.ZERO;
            BigDecimal guaranteeFill = BigDecimal.ZERO;

            if (role == EmployeeRole.MANAGER) {
                // 团队提成 = 门店新签合计 × 10%
                BigDecimal teamRate = bd(rank.path("teamRate").asText("0.10"));
                BigDecimal deptTotal = input.deptNewSignTotal.getOrDefault(emp.getDeptId(), BigDecimal.ZERO);
                teamIncome = MoneyUtil.round2(deptTotal.multiply(teamRate));
                d.setTeamIncome(teamIncome);

                // 个人新签提成 = 个人新签 × 70% → 递延
                BigDecimal personalRate = bd(rank.path("personalRate").asText("0.70"));
                BigDecimal personalNewsignPerf = sumAmount(input.newsignByEmp.get(emp.getEmployeeId()));
                personalNewsign = MoneyUtil.round2(personalNewsignPerf.multiply(personalRate));
                d.setPersonalNewsignIncome(personalNewsign);

                // 保底补足 = MAX(min, team+ps) - ps - team
                BigDecimal minSalary = bd(rank.path("minSalary").asText("0"));
                BigDecimal sumTp = teamIncome.add(personalNewsign);
                BigDecimal maxOfMin = minSalary.max(sumTp);
                guaranteeFill = MoneyUtil.round2(maxOfMin.subtract(personalNewsign).subtract(teamIncome));
                if (guaranteeFill.compareTo(BigDecimal.ZERO) < 0) {
                    guaranteeFill = BigDecimal.ZERO;
                }
                d.setGuaranteeFill(guaranteeFill);

            } else if (role == EmployeeRole.DIRECTOR) {
                // 底薪 6000
                baseSalary = bd(rank.path("baseSalary").asText("6000"));
                d.setBaseSalary(MoneyUtil.round2(baseSalary));

                // 门店提成：逐店新签 × 跳点比例（总监管多店时按店汇总）
                storeIncome = calcDirectorStoreIncome(emp, input, rank);
                d.setStoreIncome(storeIncome);
            } else {
                // 经纪人底薪：从职级规则快照通用读取（A0 实习期、C0/C1 新人保护期等）
                baseSalary = bd(rank.path("baseSalary").asText("0"));
            }
            d.setBaseSalary(MoneyUtil.round2(baseSalary));

            // 奖金 / 其他收入
            BigDecimal bonus = input.manualIncome.getOrDefault(emp.getEmployeeId(), BigDecimal.ZERO);
            d.setBonus(MoneyUtil.round2(bonus));
            d.setOtherIncome(BigDecimal.ZERO);

            // 应发 = 提成 + 团队 + 保底 + 门店 + 底薪 + 招聘奖励 + 奖金（不含个人新签递延）
            BigDecimal gross = commissionIncome.add(teamIncome).add(guaranteeFill)
                .add(storeIncome).add(baseSalary).add(mentorBonus).add(bonus);
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

            // 考勤扣款
            BigDecimal attendanceFee = input.attendanceFee.getOrDefault(emp.getEmployeeId(), BigDecimal.ZERO);
            d.setAttendanceFee(MoneyUtil.round2(attendanceFee));

            // 积分扣款（总监不扣）
            BigDecimal pointsFee = BigDecimal.ZERO;
            if (role != EmployeeRole.DIRECTOR) {
                pointsFee = input.pointsFee.getOrDefault(emp.getEmployeeId(), BigDecimal.ZERO);
            }
            d.setPointsFee(MoneyUtil.round2(pointsFee));

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

            // 其他支出
            BigDecimal otherDeduct = input.manualDeduct.getOrDefault(emp.getEmployeeId(), BigDecimal.ZERO);
            d.setOtherDeduct(MoneyUtil.round2(otherDeduct));

            // 支出合计
            BigDecimal deduct = socialFee.add(housingFund).add(attendanceFee).add(pointsFee)
                .add(commercialInsurance).add(dormitoryFee).add(negativeCarryover).add(otherDeduct);
            d.setDeduct(MoneyUtil.round2(deduct));

            // 个税（累计预扣）
            BigDecimal netBeforeTax = gross.subtract(deduct);
            BigDecimal tax = calcTax(emp, netBeforeTax, input, snap);
            d.setTax(MoneyUtil.round2(tax));

            // 最终发放
            BigDecimal net = netBeforeTax.subtract(tax);
            d.setNet(MoneyUtil.round2(net));

            details.add(d);
        }
        return details;
    }

    // ==================== 辅助方法 ====================

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

    private BigDecimal resolvePerfDeduct(RuleService.ParsedSnapshot snap, String grade) {
        JsonNode points = snap.policy().path("points");
        String key = "deduct" + grade;
        return bd(points.path(key).asText("0"));
    }

    private BigDecimal calcDirectorStoreIncome(EmployeeSnapshot director, CalcInput input, JsonNode rank) {
        // 简化：总监按所管门店新签合计套用跳点档位
        // 实际应逐店算后汇总；此处用 director dept 的新签合计
        BigDecimal total = input.deptNewSignTotal.getOrDefault(director.getDeptId(), BigDecimal.ZERO);
        JsonNode brackets = rank.path("ruleContent").path("brackets");
        if (!brackets.isArray() || brackets.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = BigDecimal.ZERO;
        for (JsonNode b : brackets) {
            BigDecimal min = bd(b.path("min").asText("0"));
            if (total.compareTo(min) >= 0) {
                rate = bd(b.path("rate").asText("0"));
            }
        }
        return MoneyUtil.round2(total.multiply(rate));
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

    private BigDecimal sumAmount(List<CommissionItemDTO> items) {
        if (items == null) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (CommissionItemDTO it : items) {
            if (it.getAmount() != null) {
                sum = sum.add(it.getAmount());
            }
        }
        return sum;
    }

    private BigDecimal bd(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        return new BigDecimal(s);
    }
}
