package com.panjia.payroll.service;

import com.panjia.contracts.dto.AttendanceMetricsDTO;
import com.panjia.contracts.dto.CommissionItemDTO;
import com.panjia.contracts.snapshot.EmployeeSnapshot;
import com.panjia.payroll.domain.EmployeeRole;
import com.panjia.payroll.domain.PayrollDetail;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 算薪引擎单元测试（对应设计 V1.0 §10.1 / §10.2 核心用例）。
 */
@Tag("dev")
class SalaryCalculationEngineTest {

    private final SalaryCalculationEngine engine = new SalaryCalculationEngine();

    private RuleService.ParsedSnapshot buildSnapshot() {
        return buildSnapshot("0.02");
    }

    /** 店长 S1 的金额比例可指定，用于验证不同新签月配置比例变化 */
    private RuleService.ParsedSnapshot buildSnapshot(String s1AmountRatio) {
        String json = """
        {
          "rank": {
            "A2": {"baseSalary":"0","baseRate":"0.60","minSalary":"0","ruleContent":{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":"0.02","mentorBonusRateCap":"0.10"}},
            "A0": {"baseSalary":"4500","baseRate":"0.55","minSalary":"4500","ruleContent":{}},
            "A9": {"baseSalary":"0","baseRate":"0.60","minSalary":"0","ruleContent":{"mentorBonusMode":"AMOUNT_RATIO","mentorBonusAmountRatio":"0.03"}},
            "S1": {"baseSalary":"0","baseRate":"0.30","minSalary":"8000","teamRate":"0.10","personalRate":"0.70","ruleContent":{"mentorBonusMode":"AMOUNT_RATIO","mentorBonusAmountRatio":"%s"}},
            "D":  {"baseSalary":"6000","baseRate":"0.30","minSalary":"0","ruleContent":{"brackets":[{"min":0,"rate":0.06},{"min":100000,"rate":0.07}],"mentorBonusMode":"AMOUNT_RATIO","mentorBonusAmountRatio":"0.02"}}
          },
          "policy": {
            "baseSocial":"1637.15",
            "socialSettlementRatio":{"A0":0.20,"A1":0.55,"A2":0.60,"A3":0.65,"A4":0.67,"A5":0.70,"S1":0.30,"S2":0.30,"D":0.30},
            "commercialInsurance":21,
            "dormitoryFee":0,
            "housingFund":0,
            "tax":{"mode":"ENGINE","threshold":5000,"deductSocial":true,"brackets":[{"min":0,"rate":0.03,"quick":0},{"min":3000,"rate":0.10,"quick":210}]},
            "points":{"penaltyFee":5,"gradeA":8.0,"gradeB":6.0,"deductA":0.0,"deductB":-0.02,"deductC":-0.04},
            "attendance":{"lateFee":20,"absentNoBaseFee":50,"absentWithBaseTimes":3,"personalLeaveFee":50,"personalLeaveDeductDailyWage":true,"sickClinicWithBaseFee":30,"sickClinicNoBaseFee":20,"sickHospitalWithBaseFee":20,"sickHospitalNoBaseFee":0,"leaveFee":50}
          },
          "conversion": {"一手房":0.9024,"DEFAULT":0.96}
        }
        """.formatted(s1AmountRatio);
        RuleService svc = new RuleService(null, null, null, null, null, new tools.jackson.databind.ObjectMapper());
        return svc.parseSnapshot(json);
    }

    /** 等级 A 的 scoreFacts：totalPoints/attendDays = 200/22 ≈ 9.09 ≥ policy.points.gradeA=8 */
    private static com.panjia.contracts.dto.ScoreFactsDTO factsA() {
        com.panjia.contracts.dto.ScoreFactsDTO f = new com.panjia.contracts.dto.ScoreFactsDTO();
        f.setTotalPoints(new BigDecimal("200"));
        f.setAttendDays(22);
        f.setLateSubmitCount(0);
        return f;
    }

    /** 等级 B 的 scoreFacts：totalPoints/attendDays = 150/22 ≈ 6.82 ∈ [gradeB=6, gradeA=8) */
    private static com.panjia.contracts.dto.ScoreFactsDTO factsB() {
        com.panjia.contracts.dto.ScoreFactsDTO f = new com.panjia.contracts.dto.ScoreFactsDTO();
        f.setTotalPoints(new BigDecimal("150"));
        f.setAttendDays(22);
        f.setLateSubmitCount(0);
        return f;
    }

    private EmployeeSnapshot emp(String code, String level, String position) {
        EmployeeSnapshot e = new EmployeeSnapshot();
        e.setEmployeeId(100L + code.hashCode() % 1000);
        e.setEmployeeCode(code);
        e.setEmployeeName(code);
        e.setDeptId(1L);
        e.setLevelCode(level);
        e.setPosition(position);
        e.setSocialInsured(true);
        e.setIsPartTime(false);
        e.setCommercialInsured(false);
        e.setDormitory(false);
        e.setHousingInsured(false);
        return e;
    }

    private CommissionItemDTO item(Long empId, BigDecimal amount, String bizType) {
        CommissionItemDTO it = new CommissionItemDTO();
        it.setEmployeeId(empId);
        it.setAmount(amount);
        it.setBizType(bizType);
        it.setDeptId(1L);
        return it;
    }

    /** S-1: 经纪人 A2 结佣 100,000 × 60% = 60,000 */
    @Test
    void testAgentA2Commission() {
        EmployeeSnapshot e = emp("A2001", "A2", "经纪人");
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.employees = List.of(e);
        input.lockedByEmp = Map.of(e.getEmployeeId(), List.of(item(e.getEmployeeId(), new BigDecimal("100000"), "SECOND_HAND")));
        input.newsignByEmp = new HashMap<>();
        input.deptNewSignTotal = Map.of(1L, BigDecimal.ZERO);
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(e.getEmployeeId(), 1);
        input.scoreFacts = Map.of(e.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = new HashMap<>();

        List<PayrollDetail> details = engine.calculate(input);
        assertEquals(1, details.size());
        PayrollDetail d = details.get(0);
        // 结佣折算后：100000 × 0.96 × 60% = 57600
        assertEquals(0, new BigDecimal("57600.00").compareTo(d.getCommissionIncome()));
        assertEquals(0, BigDecimal.ZERO.compareTo(d.getBaseSalary()));
        // 社保 A2: 1637.15 × 60% = 982.29
        assertEquals(0, new BigDecimal("982.29").compareTo(d.getSocialFee()));
        // 公司社保: 1637.15 × 40% = 654.86
        assertEquals(0, new BigDecimal("654.86").compareTo(d.getEmployerSocial()));
        // 应发 = 57600
        assertEquals(0, new BigDecimal("57600.00").compareTo(d.getGross()));
        assertTrue(d.getNet().compareTo(BigDecimal.ZERO) > 0);
    }

    /** S-6: 店长保底未触发 — 团队 9000 + 个人新签 3000 = 12000 ≥ 8000 → 当月发 9000，个人新签递延 */
    @Test
    void testManagerGuaranteeNotTriggered() {
        EmployeeSnapshot mgr = emp("M001", "S1", "店长");
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.employees = List.of(mgr);
        input.lockedByEmp = Map.of(mgr.getEmployeeId(), new ArrayList<>());
        // 个人新签 3000 → ×70% = 2100 递延
        input.newsignByEmp = Map.of(mgr.getEmployeeId(), List.of(item(mgr.getEmployeeId(), new BigDecimal("3000"), "SECOND_HAND")));
        // 门店新签合计 90000 → ×10% = 9000
        input.deptNewSignTotal = Map.of(1L, new BigDecimal("90000"));
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(mgr.getEmployeeId(), 1);
        input.scoreFacts = Map.of(mgr.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = new HashMap<>();

        PayrollDetail d = engine.calculate(input).get(0);
        // 店长 finalRate = personalRate 0.70（非 baseRate 0.30）
        assertEquals(0, new BigDecimal("0.70").compareTo(d.getFinalRate()));
        // 团队提成 = (90000 - 店长本人新签折算 3000×0.96=2880) × 10% = 8712
        assertEquals(0, new BigDecimal("8712.00").compareTo(d.getTeamIncome()));
        // 个人新签折算后：3000 × 0.96 × 70% = 2016（递延，不进 gross）
        assertEquals(0, new BigDecimal("2016.00").compareTo(d.getPersonalNewsignIncome()));
        // 保底补足 = MAX(8000, 8712+2016) - 2016 - 8712 = 10728 - 10728 = 0
        assertEquals(0, BigDecimal.ZERO.compareTo(d.getGuaranteeFill()));
        // 应发 = 团队 + 保底 = 8712 + 0 = 8712
        assertEquals(0, new BigDecimal("8712.00").compareTo(d.getGross()));
    }

    /** S-7: 店长保底触发 — 团队 2000 + 个人新签 1000 = 3000 < 8000 → 补足 5000，当月发 7000 */
    @Test
    void testManagerGuaranteeTriggered() {
        EmployeeSnapshot mgr = emp("M002", "S1", "店长");
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.employees = List.of(mgr);
        input.lockedByEmp = Map.of(mgr.getEmployeeId(), new ArrayList<>());
        input.newsignByEmp = Map.of(mgr.getEmployeeId(), List.of(item(mgr.getEmployeeId(), new BigDecimal("1000"), "SECOND_HAND")));
        input.deptNewSignTotal = Map.of(1L, new BigDecimal("20000")); // 20000×10% = 2000
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(mgr.getEmployeeId(), 1);
        input.scoreFacts = Map.of(mgr.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = new HashMap<>();

        PayrollDetail d = engine.calculate(input).get(0);
        // 团队 = (20000 - 店长本人新签折算 1000×0.96=960) × 10% = 1904, 个人新签折算后递延 = 672
        assertEquals(0, new BigDecimal("1904.00").compareTo(d.getTeamIncome()));
        assertEquals(0, new BigDecimal("672.00").compareTo(d.getPersonalNewsignIncome()));
        // 保底补足 = MAX(8000, 1904+672) - 672 - 1904 = 8000 - 2576 = 5424
        assertEquals(0, new BigDecimal("5424.00").compareTo(d.getGuaranteeFill()));
        // 应发 = 1904 + 5424 = 7328，保底兜底至 minSalary+commissionIncome = 8000
        assertEquals(0, new BigDecimal("8000.00").compareTo(d.getGross()));
    }

    /** S-19: 店长结佣提成用 personalRate 70% — 结佣 100000 × 0.96 × 70% = 67200 */
    @Test
    void testManagerCommissionUsesPersonalRate() {
        EmployeeSnapshot mgr = emp("M020", "S1", "店长");
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.employees = List.of(mgr);
        // 店长本人有结佣 100000（SECOND_HAND 折算 0.96）
        input.lockedByEmp = Map.of(mgr.getEmployeeId(),
            List.of(item(mgr.getEmployeeId(), new BigDecimal("100000"), "SECOND_HAND")));
        input.newsignByEmp = new HashMap<>();
        input.deptNewSignTotal = Map.of(1L, BigDecimal.ZERO);
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(mgr.getEmployeeId(), 1);
        input.scoreFacts = Map.of(mgr.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = new HashMap<>();

        PayrollDetail d = engine.calculate(input).get(0);
        // finalRate = personalRate 0.70（非 baseRate 0.30）
        assertEquals(0, new BigDecimal("0.70").compareTo(d.getFinalRate()));
        // 结佣业绩折算后 = 100000 × 0.96 = 96000
        assertEquals(0, new BigDecimal("96000.00").compareTo(d.getCommissionPerformance()));
        // 提成金额 = 96000 × 70% = 67200
        assertEquals(0, new BigDecimal("67200.00").compareTo(d.getCommissionIncome()));
    }

    /** S-14: 折算作用于新签和结佣 — 一手房结佣 100000 → 折算 90240 × 60% = 54144 */
    @Test
    void testConversionAppliesToAll() {
        EmployeeSnapshot e = emp("A2002", "A2", "经纪人");
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.employees = List.of(e);
        // 结佣 100000（一手房，biz_type 与业绩事实同为中文）折算后 90240
        input.lockedByEmp = Map.of(e.getEmployeeId(), List.of(item(e.getEmployeeId(), new BigDecimal("100000"), "一手房")));
        input.newsignByEmp = new HashMap<>();
        input.deptNewSignTotal = new HashMap<>();
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(e.getEmployeeId(), 1);
        input.scoreFacts = Map.of(e.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = new HashMap<>();

        PayrollDetail d = engine.calculate(input).get(0);
        // 结佣折算后：100000 × 0.9024 = 90240（落地业绩）
        assertEquals(0, new BigDecimal("90240.00").compareTo(d.getCommissionPerformance()));
        // 提成 = 90240 × 60% = 54144
        assertEquals(0, new BigDecimal("54144.00").compareTo(d.getCommissionIncome()));
    }

    /** 总监：底薪 6000 + 门店提成（跳点） */
    @Test
    void testDirectorIncome() {
        EmployeeSnapshot dir = emp("D001", "D", "总监");
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.employees = List.of(dir);
        input.lockedByEmp = Map.of(dir.getEmployeeId(), new ArrayList<>());
        input.newsignByEmp = new HashMap<>();
        // 门店新签 120000 → 跳点 7% → 8400
        input.deptNewSignTotal = Map.of(1L, new BigDecimal("120000"));
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(dir.getEmployeeId(), 1);
        input.scoreFacts = Map.of(dir.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = new HashMap<>();

        PayrollDetail d = engine.calculate(input).get(0);
        assertEquals(0, new BigDecimal("6000.00").compareTo(d.getBaseSalary()));
        // 120000 ≥ 100000 → 7% → 8400
        assertEquals(0, new BigDecimal("8400.00").compareTo(d.getStoreIncome()));
        // 绩效 A 级不扣点：finalRate = baseRate 0.30
        assertEquals(0, new BigDecimal("0.30").compareTo(d.getFinalRate()));
        // 总监门店提成（跳点档位）不经过 finalRate，绩效等级不影响门店提成
        assertEquals("A", d.getPerfGrade());
    }

    /** S-13: 绩效扣点 — A2 经纪人 B 级（-2%）→ finalRate = 60% - 2% = 58% */
    @Test
    void testPerfGradeDeduct() {
        EmployeeSnapshot e = emp("A2003", "A2", "经纪人");
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.employees = List.of(e);
        input.lockedByEmp = Map.of(e.getEmployeeId(), List.of(item(e.getEmployeeId(), new BigDecimal("100000"), "SECOND_HAND")));
        input.newsignByEmp = new HashMap<>();
        input.deptNewSignTotal = Map.of(1L, BigDecimal.ZERO);
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(e.getEmployeeId(), 1);
        input.scoreFacts = Map.of(e.getEmployeeId(), factsB());
        input.qualifiedApprenticeCount = new HashMap<>();

        List<PayrollDetail> details = engine.calculate(input);
        PayrollDetail d = details.get(0);
        // finalRate = 0.60 - 0.02 = 0.58；结佣折算后 100000×0.96=96000 × 58% = 55680
        assertEquals("B", d.getPerfGrade());
        assertEquals(0, new BigDecimal("0.58").compareTo(d.getFinalRate()));
        assertEquals(0, new BigDecimal("55680.00").compareTo(d.getCommissionIncome()));
    }

    /** S-15: 店长团队提成扣减门店社保业绩 — (deptNewSign - deptEmployerSocial) × teamRate */
    @Test
    void testManagerTeamIncomeDeductsEmployerSocial() {
        EmployeeSnapshot mgr = emp("M010", "S1", "店长");
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.employees = List.of(mgr);
        input.lockedByEmp = Map.of(mgr.getEmployeeId(), new ArrayList<>());
        input.newsignByEmp = new HashMap<>();
        // 门店新签 90000，门店社保业绩扣款 1000（模拟 2 员工各 employerSocial 500 聚合）
        input.deptNewSignTotal = Map.of(1L, new BigDecimal("90000"));
        input.deptEmployerSocialTotal = Map.of(1L, new BigDecimal("1000"));
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(mgr.getEmployeeId(), 1);
        input.scoreFacts = Map.of(mgr.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = new HashMap<>();

        PayrollDetail d = engine.calculate(input).get(0);
        // 团队提成 = (90000 - 1000) × 10% = 8900
        assertEquals(0, new BigDecimal("8900.00").compareTo(d.getTeamIncome()));
        // 落地字段对齐天街工资表店长 sheet
        assertEquals(0, new BigDecimal("90000.00").compareTo(d.getDeptNewSignTotal()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(d.getDeptEmployerSocialTotal()));
        assertEquals(0, new BigDecimal("0.10").compareTo(d.getTeamRate()));
        assertEquals(0, new BigDecimal("8000.00").compareTo(d.getMinSalary()));
    }

    /** S-16: 总监门店提成扣减门店社保业绩 — (deptNewSign - deptEmployerSocial) × 跳点档 rate */
    @Test
    void testDirectorStoreIncomeDeductsEmployerSocial() {
        EmployeeSnapshot dir = emp("D010", "D", "总监");
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.employees = List.of(dir);
        input.lockedByEmp = Map.of(dir.getEmployeeId(), new ArrayList<>());
        input.newsignByEmp = new HashMap<>();
        // 门店新签 120000，门店社保业绩扣款 1000 → 计薪基数 119000 ≥ 100000 → 7%
        input.deptNewSignTotal = Map.of(1L, new BigDecimal("120000"));
        input.deptEmployerSocialTotal = Map.of(1L, new BigDecimal("1000"));
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(dir.getEmployeeId(), 1);
        input.scoreFacts = Map.of(dir.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = new HashMap<>();

        PayrollDetail d = engine.calculate(input).get(0);
        // 门店提成 = (120000 - 1000) × 7% = 8330
        assertEquals(0, new BigDecimal("8330.00").compareTo(d.getStoreIncome()));
        // 落地字段对齐天街工资表总监 sheet
        assertEquals(0, new BigDecimal("120000.00").compareTo(d.getDeptNewSignTotal()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(d.getDeptEmployerSocialTotal()));
        // storeRate 固定 0（跳点档位在 directorStoreItems JSON 明细中，不落地到 storeRate）
        assertEquals(0, BigDecimal.ZERO.compareTo(d.getStoreRate()));
    }

    /** S-18: 总监多门店跳点 — 3 门店各自跳点算提成，汇总 storeIncome，directorStoreItems JSON 含 3 条明细 */
    @Test
    void testDirectorMultiStore() {
        EmployeeSnapshot dir = emp("D020", "D", "总监");
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.employees = List.of(dir);
        input.lockedByEmp = Map.of(dir.getEmployeeId(), new ArrayList<>());
        input.newsignByEmp = new HashMap<>();
        // 3 门店新签与社保
        input.deptNewSignTotal = new HashMap<>();
        input.deptNewSignTotal.put(1L, new BigDecimal("120000")); // ≥10万 → 7%
        input.deptNewSignTotal.put(2L, new BigDecimal("80000"));  // <10万 → 6%
        input.deptNewSignTotal.put(3L, new BigDecimal("50000"));  // <10万 → 6%
        input.deptEmployerSocialTotal = new HashMap<>();
        input.deptEmployerSocialTotal.put(1L, new BigDecimal("1000"));
        input.deptEmployerSocialTotal.put(2L, BigDecimal.ZERO);
        input.deptEmployerSocialTotal.put(3L, new BigDecimal("500"));
        // 总监管辖 3 门店
        input.directorStoreDepts = Map.of(dir.getEmployeeId(), List.of(1L, 2L, 3L));
        input.deptNames = Map.of(1L, "龙湖店", 2L, "云庭店", 3L, "长庆店");
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(dir.getEmployeeId(), 1);
        input.scoreFacts = Map.of(dir.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = new HashMap<>();

        PayrollDetail d = engine.calculate(input).get(0);
        // 门店1: (120000-1000) × 7% = 8330
        // 门店2: (80000-0) × 6% = 4800
        // 门店3: (50000-500) × 6% = 2970
        // 合计 = 8330 + 4800 + 2970 = 16100
        assertEquals(0, new BigDecimal("16100.00").compareTo(d.getStoreIncome()));
        // 汇总新签 = 250000
        assertEquals(0, new BigDecimal("250000.00").compareTo(d.getDeptNewSignTotal()));
        // 汇总社保 = 1500
        assertEquals(0, new BigDecimal("1500.00").compareTo(d.getDeptEmployerSocialTotal()));
        // 底薪 6000
        assertEquals(0, new BigDecimal("6000.00").compareTo(d.getBaseSalary()));
        // storeRate 固定 0（跳点档位在 JSON 明细中）
        assertEquals(0, BigDecimal.ZERO.compareTo(d.getStoreRate()));
        // directorStoreItems JSON 非空，含 3 条门店明细
        assertNotNull(d.getDirectorStoreItems());
        assertTrue(d.getDirectorStoreItems().contains("龙湖店"));
        assertTrue(d.getDirectorStoreItems().contains("云庭店"));
        assertTrue(d.getDirectorStoreItems().contains("长庆店"));
        // 应发 = 底薪 + 门店提成 = 6000 + 16100 = 22100
        assertEquals(0, new BigDecimal("22100.00").compareTo(d.getGross()));
    }

    /**
     * S-12（2026-11 规则）: 考勤扣款。归属期 2026-07（31 天），日工资 = 底薪/31；底薪与保底均为 0 时回退固定扣款。
     * A2 无底薪无保底：迟到 3×20 + 旷工 2×(50×3) + 事假 1×50 + 病假诊所 1×20 + 病假二甲 1×0 + 导入 10 = 440
     * A0 底薪 4500（日工资 4500/31=145.161290）：
     *   迟到 20 + 旷工 3×145.161290 + 事假 145.161290 + 病假诊所 30 + 病假二甲 20 = 650.65
     */
    @Test
    void testAttendanceFeeFromMetrics() {
        EmployeeSnapshot a2 = emp("A2010", "A2", "经纪人"); // 无底薪（baseSalary=0）
        EmployeeSnapshot a0 = emp("A0010", "A0", "经纪人"); // 底薪 4500
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.period = "2026-07";
        input.employees = List.of(a2, a0);
        input.lockedByEmp = Map.of(a2.getEmployeeId(), new ArrayList<>(), a0.getEmployeeId(), new ArrayList<>());
        input.newsignByEmp = new HashMap<>();
        input.deptNewSignTotal = new HashMap<>();
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(a2.getEmployeeId(), 1, a0.getEmployeeId(), 1);
        input.scoreFacts = Map.of(a2.getEmployeeId(), factsA(), a0.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = new HashMap<>();

        // A2（无底薪）：迟到 3 次 + 旷工 2 天 + 事假 1 天 + 两类病假各 1 天 + 导入金额 10
        AttendanceMetricsDTO m2 = new AttendanceMetricsDTO();
        m2.setImportedFee(new BigDecimal("10"));
        m2.setLateCount(3);
        m2.setAbsentDays(new BigDecimal("2"));
        m2.setPersonalLeaveDays(BigDecimal.ONE);
        m2.setSickClinicDays(BigDecimal.ONE);
        m2.setSickHospitalDays(BigDecimal.ONE);
        // A0（底薪 4500）：迟到 1 次 + 旷工 1 天 + 事假 1 天 + 两类病假各 1 天
        AttendanceMetricsDTO m0 = new AttendanceMetricsDTO();
        m0.setImportedFee(BigDecimal.ZERO);
        m0.setLateCount(1);
        m0.setAbsentDays(BigDecimal.ONE);
        m0.setPersonalLeaveDays(BigDecimal.ONE);
        m0.setSickClinicDays(BigDecimal.ONE);
        m0.setSickHospitalDays(BigDecimal.ONE);
        input.attendanceMetrics = Map.of(a2.getEmployeeId(), m2, a0.getEmployeeId(), m0);

        List<PayrollDetail> details = engine.calculate(input);
        PayrollDetail d2 = details.stream().filter(x -> x.getEmployeeId().equals(a2.getEmployeeId())).findFirst().orElseThrow();
        PayrollDetail d0 = details.stream().filter(x -> x.getEmployeeId().equals(a0.getEmployeeId())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("440.00").compareTo(d2.getAttendanceFee()));
        assertEquals(0, new BigDecimal("650.65").compareTo(d0.getAttendanceFee()));
    }

    /** 旷工扣款封顶到当月底薪/保底；兼职不参与考勤扣款 */
    @Test
    void testAttendanceCapAndPartTime() {
        EmployeeSnapshot a0 = emp("A0030", "A0", "经纪人"); // 底薪 4500
        EmployeeSnapshot pt = emp("A0031", "A0", "经纪人");
        pt.setIsPartTime(true);
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.period = "2026-07";
        input.employees = List.of(a0, pt);
        input.lockedByEmp = Map.of(a0.getEmployeeId(), new ArrayList<>(), pt.getEmployeeId(), new ArrayList<>());
        input.newsignByEmp = new HashMap<>();
        input.deptNewSignTotal = new HashMap<>();
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(a0.getEmployeeId(), 1, pt.getEmployeeId(), 1);
        input.scoreFacts = Map.of(a0.getEmployeeId(), factsA(), pt.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = new HashMap<>();

        AttendanceMetricsDTO m0 = new AttendanceMetricsDTO();
        m0.setAbsentDays(new BigDecimal("20"));
        AttendanceMetricsDTO mpt = new AttendanceMetricsDTO();
        mpt.setLateCount(3);
        mpt.setAbsentDays(new BigDecimal("26"));
        mpt.setPersonalLeaveDays(BigDecimal.ONE);
        input.attendanceMetrics = Map.of(a0.getEmployeeId(), m0, pt.getEmployeeId(), mpt);

        List<PayrollDetail> details = engine.calculate(input);
        PayrollDetail d0 = details.stream().filter(x -> x.getEmployeeId().equals(a0.getEmployeeId())).findFirst().orElseThrow();
        PayrollDetail dpt = details.stream().filter(x -> x.getEmployeeId().equals(pt.getEmployeeId())).findFirst().orElseThrow();
        // 旷工 20 × 145.161290 × 3 = 8709.68 > 底薪 4500 → 封顶 4500
        assertEquals(0, new BigDecimal("4500.00").compareTo(d0.getAttendanceFee()));
        // 兼职不参与考勤扣款
        assertEquals(0, BigDecimal.ZERO.compareTo(dpt.getAttendanceFee()));
    }

    /** 旧版合计请假兜底：仅有 leaveDays（无三类分项）时按 leaveFee(50)/天扣款，2 天 = 100 */
    @Test
    void testAttendanceFeeLegacyLeaveDaysFallback() {
        EmployeeSnapshot a2 = emp("A2011", "A2", "经纪人");
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.period = "2026-07";
        input.employees = List.of(a2);
        input.lockedByEmp = Map.of(a2.getEmployeeId(), new ArrayList<>());
        input.newsignByEmp = new HashMap<>();
        input.deptNewSignTotal = new HashMap<>();
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(a2.getEmployeeId(), 1);
        input.scoreFacts = Map.of(a2.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = new HashMap<>();

        AttendanceMetricsDTO m = new AttendanceMetricsDTO();
        m.setLateCount(0);
        m.setAbsentDays(BigDecimal.ZERO);
        // 分项显式置 0（模拟迁移后的存量行），合计 leaveDays=2
        m.setPersonalLeaveDays(BigDecimal.ZERO);
        m.setSickClinicDays(BigDecimal.ZERO);
        m.setSickHospitalDays(BigDecimal.ZERO);
        m.setLeaveDays(new BigDecimal("2"));
        input.attendanceMetrics = Map.of(a2.getEmployeeId(), m);

        PayrollDetail d = engine.calculate(input).get(0);
        assertEquals(0, new BigDecimal("100.00").compareTo(d.getAttendanceFee()));
    }

    /**
     * 入职当月底薪折算（总监 D，底薪 6000，归属期 2026-07 共 31 天）：
     * 7-15 入职 → 在职 17 天（含入职日）→ 底薪 = 6000/31×17 = 3290.32，应发 = 底薪 + 全勤 500 = 3790.32；
     * 7-1 入职 → 满月底薪 6000，应发 6500。
     */
    @Test
    void testNewHireBaseSalaryProration() {
        EmployeeSnapshot mid = emp("D1001", "D", "总监");
        mid.setHireDate(java.time.LocalDate.of(2026, 7, 15));
        EmployeeSnapshot first = emp("D1002", "D", "总监");
        first.setHireDate(java.time.LocalDate.of(2026, 7, 1));
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.period = "2026-07";
        input.employees = List.of(mid, first);
        input.lockedByEmp = Map.of(mid.getEmployeeId(), new ArrayList<>(), first.getEmployeeId(), new ArrayList<>());
        input.newsignByEmp = new HashMap<>();
        input.deptNewSignTotal = new HashMap<>();
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(mid.getEmployeeId(), 1, first.getEmployeeId(), 1);
        input.scoreFacts = Map.of(mid.getEmployeeId(), factsA(), first.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = new HashMap<>();

        List<PayrollDetail> details = engine.calculate(input);
        PayrollDetail dMid = details.stream().filter(x -> x.getEmployeeId().equals(mid.getEmployeeId())).findFirst().orElseThrow();
        PayrollDetail dFirst = details.stream().filter(x -> x.getEmployeeId().equals(first.getEmployeeId())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("3290.32").compareTo(dMid.getBaseSalary()));
        assertEquals(0, new BigDecimal("3790.32").compareTo(dMid.getGross()));
        assertEquals(0, new BigDecimal("6000.00").compareTo(dFirst.getBaseSalary()));
        assertEquals(0, new BigDecimal("6500.00").compareTo(dFirst.getGross()));
    }

    /** S-19: 店长团队提成 = (门店新签 - 社保标准×计缴人数 - 新签与结佣差额) × teamRate。
     *  注：deptStoreAnchor（组别→门店归一）特性尚未在引擎实现，此处店长直接挂门店级 deptId=1。 */
    @Test
    void testManagerTeamIncomeDeductsSocialStandardAndDiff() {
        EmployeeSnapshot mgr = emp("M020", "S1", "店长");
        mgr.setDeptId(1L); // 挂门店级（deptStoreAnchor 未实现，直接用门店ID）
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.employees = List.of(mgr);
        input.lockedByEmp = Map.of(mgr.getEmployeeId(), new ArrayList<>());
        input.newsignByEmp = new HashMap<>();
        // 门店级聚合（key=门店 1）
        input.deptNewSignTotal = Map.of(1L, new BigDecimal("90000"));
        input.deptEmployerSocialTotal = Map.of(1L, new BigDecimal("1000")); // 标准500×2人
        input.deptSocialStandard = Map.of(1L, new BigDecimal("500"));
        input.deptInsuredCount = Map.of(1L, 2);
        input.deptDiffTotal = Map.of(1L, new BigDecimal("500"));
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(mgr.getEmployeeId(), 1);
        input.scoreFacts = Map.of(mgr.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = new HashMap<>();

        PayrollDetail d = engine.calculate(input).get(0);
        // 团队提成 = (90000 - 1000 - 500) × 10% = 8850
        assertEquals(0, new BigDecimal("8850.00").compareTo(d.getTeamIncome()));
        // 落地展示字段
        assertEquals(0, new BigDecimal("90000.00").compareTo(d.getDeptNewSignTotal()));
        assertEquals(0, new BigDecimal("500.00").compareTo(d.getDeptSocialStandard()));
        assertEquals(2, d.getDeptInsuredCount());
        assertEquals(0, new BigDecimal("1000.00").compareTo(d.getDeptEmployerSocialTotal()));
        assertEquals(0, new BigDecimal("500.00").compareTo(d.getDeptDiffAmount()));
    }

    /** S-20: 总监门店提成扣减新签与结佣差额 — 差额后计薪业绩 99000 < 10万，落 6% 档 */
    @Test
    void testDirectorStoreIncomeDeductsDiff() {
        EmployeeSnapshot dir = emp("D030", "D", "总监");
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.employees = List.of(dir);
        input.lockedByEmp = Map.of(dir.getEmployeeId(), new ArrayList<>());
        input.newsignByEmp = new HashMap<>();
        // 新签 120000 - 社保 1000 - 差额 20000 = 99000 < 100000 → 6%
        input.deptNewSignTotal = Map.of(1L, new BigDecimal("120000"));
        input.deptEmployerSocialTotal = Map.of(1L, new BigDecimal("1000"));
        input.deptDiffTotal = Map.of(1L, new BigDecimal("20000"));
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(dir.getEmployeeId(), 1);
        input.scoreFacts = Map.of(dir.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = new HashMap<>();

        PayrollDetail d = engine.calculate(input).get(0);
        // 门店提成 = (120000 - 1000 - 20000) × 6% = 5940
        assertEquals(0, new BigDecimal("5940.00").compareTo(d.getStoreIncome()));
        assertEquals(0, new BigDecimal("20000.00").compareTo(d.getDeptDiffAmount()));
        // JSON 明细含差额与计薪业绩
        assertNotNull(d.getDirectorStoreItems());
        assertTrue(d.getDirectorStoreItems().contains("20000"));
        assertTrue(d.getDirectorStoreItems().contains("99000"));
    }

    /**
     * 招聘加点按「入职当月生效」切片：7月入职的千里马，7月新签即享受 +2%；
     * 该期间切片存在但无千里马时不得回退算薪月口径。
     */
    @Test
    void testMentorRateAddFromApprenticeHireMonth() {
        EmployeeSnapshot agent = emp("A2003", "A2", "经纪人");
        CommissionItemDTO julyItem = item(agent.getEmployeeId(), new BigDecimal("100000"), "SECOND_HAND");
        julyItem.setPerformanceFactId(11L);
        CommissionItemDTO augustItem = item(agent.getEmployeeId(), new BigDecimal("100000"), "SECOND_HAND");
        augustItem.setPerformanceFactId(12L);

        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.period = "2026-09";
        input.employees = List.of(agent);
        input.lockedByEmp = Map.of(agent.getEmployeeId(), List.of(julyItem, augustItem));
        input.newsignByEmp = new HashMap<>();
        input.deptNewSignTotal = Map.of(1L, BigDecimal.ZERO);
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(agent.getEmployeeId(), 1);
        input.scoreFacts = Map.of(agent.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = Map.of(agent.getEmployeeId(), 1);
        input.factPeriodMap = Map.of(11L, "2026-07", 12L, "2026-08");
        // 千里马 2026-07 入职：入职当月即 1 个千里马
        input.periodApprenticeMap = Map.of(
            "2026-07", Map.of(agent.getEmployeeId(), 1),
            "2026-08", Map.of(agent.getEmployeeId(), 1));

        PayrollDetail d = engine.calculate(input).get(0);
        // 7月：100000×0.96×62% = 59520；8月：100000×0.96×62% = 59520
        assertEquals(0, new BigDecimal("119040.00").compareTo(d.getCommissionIncome()));
        // 两个期间都有伯乐加点，分笔过程落地 mentorAdd，供切换金额比例后回溯展示
        String calcProcess = d.getCommissionCalcProcess();
        assertNotNull(calcProcess);
        assertTrue(calcProcess.contains("\"month\":\"2026-07\""), calcProcess);
        assertEquals(2, calcProcess.split("\"mentorAdd\":0.02", -1).length - 1);
    }

    /** 伯乐奖励按新签导入月逐期计算：每期取该期折算业绩与该期配置比例，累加且不封顶 */
    @Test
    void testMentorBonusByNewSignPeriod() {
        EmployeeSnapshot mgr = emp("M030", "S1", "店长");
        EmployeeSnapshot apprentice = emp("A2004", "A2", "经纪人");
        apprentice.setMentorId(mgr.getEmployeeId());
        CommissionItemDTO julyItem = item(apprentice.getEmployeeId(), new BigDecimal("100000"), "SECOND_HAND");
        julyItem.setPerformanceFactId(11L);
        CommissionItemDTO augustItem = item(apprentice.getEmployeeId(), new BigDecimal("100000"), "SECOND_HAND");
        augustItem.setPerformanceFactId(12L);

        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.period = "2026-09";
        input.employees = List.of(mgr, apprentice);
        input.lockedByEmp = Map.of(
            mgr.getEmployeeId(), List.of(),
            apprentice.getEmployeeId(), List.of(julyItem, augustItem));
        input.newsignByEmp = new HashMap<>();
        input.deptNewSignTotal = Map.of(1L, BigDecimal.ZERO);
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(mgr.getEmployeeId(), 1, apprentice.getEmployeeId(), 1);
        input.scoreFacts = Map.of(mgr.getEmployeeId(), factsA(), apprentice.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = Map.of(mgr.getEmployeeId(), 1);
        input.factPeriodMap = Map.of(11L, "2026-07", 12L, "2026-08");
        input.periodMentorRelationMap = Map.of(
            "2026-07", Map.of(apprentice.getEmployeeId(), mgr.getEmployeeId()),
            "2026-08", Map.of(apprentice.getEmployeeId(), mgr.getEmployeeId()));

        PayrollDetail mgrDetail = engine.calculate(input).stream()
            .filter(x -> x.getEmployeeId().equals(mgr.getEmployeeId())).findFirst().orElseThrow();
        // 7月：96000×2%=1920；8月：96000×2%=1920，合计 3840
        assertEquals(0, new BigDecimal("3840.00").compareTo(mgrDetail.getMentorBonus()));
        String bonusProcess = mgrDetail.getMentorBonusCalcProcess();
        assertNotNull(bonusProcess);
        assertTrue(bonusProcess.contains("\"month\":\"2026-07\""), bonusProcess);
        assertTrue(bonusProcess.contains("\"month\":\"2026-08\""), bonusProcess);
        assertEquals(2, bonusProcess.split("\"base\":96000.00", -1).length - 1);
        assertEquals(2, bonusProcess.split("\"bonus\":1920.00", -1).length - 1);
    }

    /** 全部职级切换金额比例：经纪人（非店长/总监）同样按千里马结佣业绩×配置比例发放 */
    @Test
    void testAgentAmountRatioMentorBonus() {
        EmployeeSnapshot agentMentor = emp("A9001", "A9", "经纪人");
        EmployeeSnapshot apprentice = emp("A2005", "A2", "经纪人");
        apprentice.setMentorId(agentMentor.getEmployeeId());
        CommissionItemDTO augustItem = item(apprentice.getEmployeeId(), new BigDecimal("100000"), "SECOND_HAND");
        augustItem.setPerformanceFactId(12L);

        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.period = "2026-09";
        input.employees = List.of(agentMentor, apprentice);
        input.lockedByEmp = Map.of(
            agentMentor.getEmployeeId(), List.of(),
            apprentice.getEmployeeId(), List.of(augustItem));
        input.newsignByEmp = new HashMap<>();
        input.deptNewSignTotal = Map.of(1L, BigDecimal.ZERO);
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(agentMentor.getEmployeeId(), 1, apprentice.getEmployeeId(), 1);
        input.scoreFacts = Map.of(agentMentor.getEmployeeId(), factsA(), apprentice.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = Map.of(agentMentor.getEmployeeId(), 1);
        input.factPeriodMap = Map.of(12L, "2026-08");
        input.periodMentorRelationMap = Map.of(
            "2026-08", Map.of(apprentice.getEmployeeId(), agentMentor.getEmployeeId()));

        PayrollDetail agentDetail = engine.calculate(input).stream()
            .filter(x -> x.getEmployeeId().equals(agentMentor.getEmployeeId())).findFirst().orElseThrow();
        // 8月新签：100000×0.96 = 96000，×3% = 2880；RATE_ADD 关闭，加点为空
        assertEquals(0, new BigDecimal("2880.00").compareTo(agentDetail.getMentorBonus()));
        assertEquals(null, agentDetail.getMentorAdd());
        // 溯源 JSON 落地新签月/该期基数/该期比例/该期金额（前端直接展示，不再反推比例）
        String bonusProcess = agentDetail.getMentorBonusCalcProcess();
        assertNotNull(bonusProcess);
        assertTrue(bonusProcess.contains("\"month\":\"2026-08\""), bonusProcess);
        assertTrue(bonusProcess.contains("\"base\":96000.00"), bonusProcess);
        assertTrue(bonusProcess.contains("\"ratio\":0.03"), bonusProcess);
        assertTrue(bonusProcess.contains("\"bonus\":2880.00"), bonusProcess);
    }

    /** 总监伯乐奖励回归：基数 = 千里马本算薪月折算后结佣业绩合计 192000 × 规则比例 2% = 3840 */
    @Test
    void testDirectorAmountRatioMentorBonus() {
        EmployeeSnapshot dir = emp("D1010", "D", "总监");
        EmployeeSnapshot apprentice = emp("A2006", "A2", "经纪人");
        apprentice.setMentorId(dir.getEmployeeId());
        CommissionItemDTO julyItem = item(apprentice.getEmployeeId(), new BigDecimal("100000"), "SECOND_HAND");
        julyItem.setPerformanceFactId(21L);
        CommissionItemDTO augustItem = item(apprentice.getEmployeeId(), new BigDecimal("100000"), "SECOND_HAND");
        augustItem.setPerformanceFactId(22L);

        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.period = "2026-09";
        input.employees = List.of(dir, apprentice);
        input.lockedByEmp = Map.of(
            dir.getEmployeeId(), List.of(),
            apprentice.getEmployeeId(), List.of(julyItem, augustItem));
        input.newsignByEmp = new HashMap<>();
        input.deptNewSignTotal = Map.of(1L, BigDecimal.ZERO);
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(dir.getEmployeeId(), 1, apprentice.getEmployeeId(), 1);
        input.scoreFacts = Map.of(dir.getEmployeeId(), factsA(), apprentice.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = Map.of(dir.getEmployeeId(), 1);
        input.factPeriodMap = Map.of(21L, "2026-07", 22L, "2026-08");
        input.periodMentorRelationMap = Map.of(
            "2026-07", Map.of(apprentice.getEmployeeId(), dir.getEmployeeId()),
            "2026-08", Map.of(apprentice.getEmployeeId(), dir.getEmployeeId()));

        PayrollDetail dirDetail = engine.calculate(input).stream()
            .filter(x -> x.getEmployeeId().equals(dir.getEmployeeId())).findFirst().orElseThrow();
        // 7月 96000×2% + 8月 96000×2% = 3840
        assertEquals(0, new BigDecimal("3840.00").compareTo(dirDetail.getMentorBonus()));
        String bonusProcess = dirDetail.getMentorBonusCalcProcess();
        assertNotNull(bonusProcess);
        // 逐期落库：两期各 96000.00，而非合并成一个 192000.00
        assertEquals(2, bonusProcess.split("\"base\":96000.00", -1).length - 1);
        assertTrue(bonusProcess.contains("\"ratio\":0.02"), bonusProcess);
    }

    /** 未绑定新签事实的结佣也按算薪月汇总：不能漏算店长/总监的金额比例奖励 */
    @Test
    void testMentorBonusUnboundPeriodFallsBackToPayrollMonth() {
        EmployeeSnapshot mgr = emp("M031", "S1", "店长");
        EmployeeSnapshot apprentice = emp("A2007", "A2", "经纪人");
        apprentice.setMentorId(mgr.getEmployeeId());
        // 无 performanceFactId → rulePeriod 为空
        CommissionItemDTO item = item(apprentice.getEmployeeId(), new BigDecimal("100000"), "SECOND_HAND");

        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.period = "2026-09";
        input.employees = List.of(mgr, apprentice);
        input.lockedByEmp = Map.of(
            mgr.getEmployeeId(), List.of(),
            apprentice.getEmployeeId(), List.of(item));
        input.newsignByEmp = new HashMap<>();
        input.deptNewSignTotal = Map.of(1L, BigDecimal.ZERO);
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(mgr.getEmployeeId(), 1, apprentice.getEmployeeId(), 1);
        input.scoreFacts = Map.of(mgr.getEmployeeId(), factsA(), apprentice.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = Map.of(mgr.getEmployeeId(), 1);
        input.factPeriodMap = new HashMap<>();
        input.periodMentorRelationMap = Map.of(
            "2026-09", Map.of(apprentice.getEmployeeId(), mgr.getEmployeeId()));

        PayrollDetail mgrDetail = engine.calculate(input).stream()
            .filter(x -> x.getEmployeeId().equals(mgr.getEmployeeId())).findFirst().orElseThrow();
        // 折算后结佣业绩 100000×0.96 = 96000，×2% = 1920，归到算薪月
        assertEquals(0, new BigDecimal("1920.00").compareTo(mgrDetail.getMentorBonus()));
        String bonusProcess = mgrDetail.getMentorBonusCalcProcess();
        assertNotNull(bonusProcess);
        assertTrue(bonusProcess.contains("\"month\":\"2026-09\""), bonusProcess);
        assertTrue(bonusProcess.contains("\"base\":96000.00"), bonusProcess);
    }

    /** 算薪月无有效伯乐关系（千里马已离职或关系未生效）：金额比例为 0 */
    @Test
    void testAmountRatioBonusZeroWithoutEffectiveRelation() {
        EmployeeSnapshot mgr = emp("M032", "S1", "店长");
        EmployeeSnapshot apprentice = emp("A2008", "A2", "经纪人");
        apprentice.setMentorId(mgr.getEmployeeId());
        CommissionItemDTO item = item(apprentice.getEmployeeId(), new BigDecimal("100000"), "SECOND_HAND");

        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.period = "2026-09";
        input.employees = List.of(mgr, apprentice);
        input.lockedByEmp = Map.of(
            mgr.getEmployeeId(), List.of(),
            apprentice.getEmployeeId(), List.of(item));
        input.newsignByEmp = new HashMap<>();
        input.deptNewSignTotal = Map.of(1L, BigDecimal.ZERO);
        input.snapshot = buildSnapshot();
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(mgr.getEmployeeId(), 1, apprentice.getEmployeeId(), 1);
        input.scoreFacts = Map.of(mgr.getEmployeeId(), factsA(), apprentice.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = Map.of(mgr.getEmployeeId(), 1);
        input.factPeriodMap = new HashMap<>();
        // 算薪月无有效伯乐关系：即使千里马当月有结佣，伯乐也不发放金额比例奖励
        input.periodMentorRelationMap = Map.of("2026-09", Map.of());

        PayrollDetail mgrDetail = engine.calculate(input).stream()
            .filter(x -> x.getEmployeeId().equals(mgr.getEmployeeId())).findFirst().orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(mgrDetail.getMentorBonus()));
    }

    /** 7月与8月配置比例不同：各期按各自比例计算，不能用算薪月比例反向套用历史新签业绩 */
    @Test
    void testAmountRatioBonusByPeriodRatio() {
        EmployeeSnapshot mgr = emp("M033", "S1", "店长");
        EmployeeSnapshot apprentice = emp("A2009", "A2", "经纪人");
        CommissionItemDTO julyItem = item(apprentice.getEmployeeId(), new BigDecimal("100000"), "SECOND_HAND");
        julyItem.setPerformanceFactId(31L);
        CommissionItemDTO augustItem = item(apprentice.getEmployeeId(), new BigDecimal("100000"), "SECOND_HAND");
        augustItem.setPerformanceFactId(32L);

        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.period = "2026-09";
        input.employees = List.of(mgr, apprentice);
        input.lockedByEmp = Map.of(
            mgr.getEmployeeId(), List.of(),
            apprentice.getEmployeeId(), List.of(julyItem, augustItem));
        input.newsignByEmp = new HashMap<>();
        input.deptNewSignTotal = Map.of(1L, BigDecimal.ZERO);
        // 算薪月 4% 不参与历史期间计算
        input.snapshot = buildSnapshot("0.04");
        input.periodSnapshots = Map.of(
            "2026-07", buildSnapshot("0.02"),
            "2026-08", buildSnapshot("0.03"));
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(mgr.getEmployeeId(), 1, apprentice.getEmployeeId(), 1);
        input.scoreFacts = Map.of(mgr.getEmployeeId(), factsA(), apprentice.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = Map.of(mgr.getEmployeeId(), 1);
        input.factPeriodMap = Map.of(31L, "2026-07", 32L, "2026-08");
        input.periodMentorRelationMap = Map.of(
            "2026-07", Map.of(apprentice.getEmployeeId(), mgr.getEmployeeId()),
            "2026-08", Map.of(apprentice.getEmployeeId(), mgr.getEmployeeId()));

        PayrollDetail mgrDetail = engine.calculate(input).stream()
            .filter(x -> x.getEmployeeId().equals(mgr.getEmployeeId())).findFirst().orElseThrow();
        // 7月：96000×2%=1920；8月：96000×3%=2880，合计 4800（不是 96000×2×4%）
        assertEquals(0, new BigDecimal("4800.00").compareTo(mgrDetail.getMentorBonus()));
        String bonusProcess = mgrDetail.getMentorBonusCalcProcess();
        assertNotNull(bonusProcess);
        assertTrue(bonusProcess.contains("\"month\":\"2026-07\""), bonusProcess);
        assertTrue(bonusProcess.contains("\"ratio\":0.02"), bonusProcess);
        assertTrue(bonusProcess.contains("\"bonus\":1920.00"), bonusProcess);
        assertTrue(bonusProcess.contains("\"month\":\"2026-08\""), bonusProcess);
        assertTrue(bonusProcess.contains("\"ratio\":0.03"), bonusProcess);
        assertTrue(bonusProcess.contains("\"bonus\":2880.00"), bonusProcess);
    }

    /** 金额比例按模式生效月：7月伯乐职级为 RATE_ADD 不加金额，8月为 AMOUNT_RATIO 才按比例发放 */
    @Test
    void testAmountRatioOnlyWhenPeriodModeIsAmountRatio() {
        EmployeeSnapshot mgr = emp("M034", "S1", "店长");
        EmployeeSnapshot apprentice = emp("A2010", "A2", "经纪人");
        CommissionItemDTO julyItem = item(apprentice.getEmployeeId(), new BigDecimal("100000"), "SECOND_HAND");
        julyItem.setPerformanceFactId(41L);
        CommissionItemDTO augustItem = item(apprentice.getEmployeeId(), new BigDecimal("100000"), "SECOND_HAND");
        augustItem.setPerformanceFactId(42L);

        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.period = "2026-09";
        input.employees = List.of(mgr, apprentice);
        input.lockedByEmp = Map.of(
            mgr.getEmployeeId(), List.of(),
            apprentice.getEmployeeId(), List.of(julyItem, augustItem));
        input.newsignByEmp = new HashMap<>();
        input.deptNewSignTotal = Map.of(1L, BigDecimal.ZERO);
        input.snapshot = buildSnapshot();
        // 7月伯乐还是 A2（RATE_ADD 模式）；8月为 S1（AMOUNT_RATIO 2%）
        input.periodLevelMap = Map.of("2026-07", Map.of(mgr.getEmployeeId(), "A2"));
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(mgr.getEmployeeId(), 1, apprentice.getEmployeeId(), 1);
        input.scoreFacts = Map.of(mgr.getEmployeeId(), factsA(), apprentice.getEmployeeId(), factsA());
        input.qualifiedApprenticeCount = Map.of(mgr.getEmployeeId(), 1);
        input.factPeriodMap = Map.of(41L, "2026-07", 42L, "2026-08");
        input.periodMentorRelationMap = Map.of(
            "2026-07", Map.of(apprentice.getEmployeeId(), mgr.getEmployeeId()),
            "2026-08", Map.of(apprentice.getEmployeeId(), mgr.getEmployeeId()));

        PayrollDetail mgrDetail = engine.calculate(input).stream()
            .filter(x -> x.getEmployeeId().equals(mgr.getEmployeeId())).findFirst().orElseThrow();
        // 7月 RATE_ADD 期间不发金额奖励，仅 8月 96000×2%=1920
        assertEquals(0, new BigDecimal("1920.00").compareTo(mgrDetail.getMentorBonus()));
        String bonusProcess = mgrDetail.getMentorBonusCalcProcess();
        assertNotNull(bonusProcess);
        assertTrue(bonusProcess.contains("\"month\":\"2026-08\""), bonusProcess);
        assertTrue(!bonusProcess.contains("\"month\":\"2026-07\""), bonusProcess);
    }
}
