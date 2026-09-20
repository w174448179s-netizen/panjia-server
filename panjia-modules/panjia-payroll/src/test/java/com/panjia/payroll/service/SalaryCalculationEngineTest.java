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
        String json = """
        {
          "rank": {
            "A2": {"baseSalary":"0","baseRate":"0.60","minSalary":"0","ruleContent":{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":"0.02","mentorBonusRateCap":"0.10"}},
            "A0": {"baseSalary":"4500","baseRate":"0.55","minSalary":"4500","ruleContent":{}},
            "S1": {"baseSalary":"0","baseRate":"0.30","minSalary":"8000","teamRate":"0.10","personalRate":"0.70","ruleContent":{"mentorBonusMode":"AMOUNT_RATIO","mentorBonusAmountRatio":"0.02"}},
            "D":  {"baseSalary":"6000","baseRate":"0.30","minSalary":"0","ruleContent":{"brackets":[{"min":0,"rate":0.06},{"min":100000,"rate":0.07}]}}
          },
          "policy": {
            "baseSocial":"1637.15",
            "socialSettlementRatio":{"A0":0.20,"A1":0.55,"A2":0.60,"A3":0.65,"A4":0.67,"A5":0.70,"S1":0.30,"S2":0.30,"D":0.30},
            "commercialInsurance":21,
            "dormitoryFee":0,
            "housingFund":0,
            "tax":{"threshold":5000,"deductSocial":true,"brackets":[{"min":0,"rate":0.03,"quick":0},{"min":3000,"rate":0.10,"quick":210}]},
            "points":{"penaltyFee":5,"gradeA":8.0,"gradeB":6.0,"deductA":0.0,"deductB":-0.02,"deductC":-0.04},
            "attendance":{"lateFee":20,"absentNoBaseFee":50,"absentWithBaseTimes":3,"workDaysPerMonth":21.75,"leaveFee":30}
          },
          "conversion": {"FIRST_HAND":0.9024,"DEFAULT":0.96}
        }
        """;
        RuleService svc = new RuleService(null, null, null, null, new tools.jackson.databind.ObjectMapper());
        return svc.parseSnapshot(json);
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
        input.manualIncome = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(e.getEmployeeId(), 1);
        input.perfGrade = Map.of(e.getEmployeeId(), "A");
        input.qualifiedApprenticeCount = new HashMap<>();
        input.apprenticeCommission = new HashMap<>();

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
        input.manualIncome = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(mgr.getEmployeeId(), 1);
        input.perfGrade = Map.of(mgr.getEmployeeId(), "A");
        input.qualifiedApprenticeCount = new HashMap<>();
        input.apprenticeCommission = new HashMap<>();

        PayrollDetail d = engine.calculate(input).get(0);
        // 店长 finalRate = personalRate 0.70（非 baseRate 0.30）
        assertEquals(0, new BigDecimal("0.70").compareTo(d.getFinalRate()));
        // 团队提成 = 90000 × 10% = 9000
        assertEquals(0, new BigDecimal("9000.00").compareTo(d.getTeamIncome()));
        // 个人新签折算后：3000 × 0.96 × 70% = 2016（递延，不进 gross）
        assertEquals(0, new BigDecimal("2016.00").compareTo(d.getPersonalNewsignIncome()));
        // 保底补足 = MAX(8000, 9000+2016) - 2016 - 9000 = 11016 - 11016 = 0
        assertEquals(0, BigDecimal.ZERO.compareTo(d.getGuaranteeFill()));
        // 应发 = 团队 + 保底 = 9000 + 0 = 9000
        assertEquals(0, new BigDecimal("9000.00").compareTo(d.getGross()));
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
        input.manualIncome = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(mgr.getEmployeeId(), 1);
        input.perfGrade = Map.of(mgr.getEmployeeId(), "A");
        input.qualifiedApprenticeCount = new HashMap<>();
        input.apprenticeCommission = new HashMap<>();

        PayrollDetail d = engine.calculate(input).get(0);
        // 团队 = 2000, 个人新签折算后递延 = 1000×0.96×70% = 672
        assertEquals(0, new BigDecimal("2000.00").compareTo(d.getTeamIncome()));
        assertEquals(0, new BigDecimal("672.00").compareTo(d.getPersonalNewsignIncome()));
        // 保底补足 = MAX(8000, 2000+672) - 672 - 2000 = 8000 - 2672 = 5328
        assertEquals(0, new BigDecimal("5328.00").compareTo(d.getGuaranteeFill()));
        // 应发 = 2000 + 5328 = 7328
        assertEquals(0, new BigDecimal("7328.00").compareTo(d.getGross()));
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
        input.manualIncome = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(mgr.getEmployeeId(), 1);
        input.perfGrade = Map.of(mgr.getEmployeeId(), "A");
        input.qualifiedApprenticeCount = new HashMap<>();
        input.apprenticeCommission = new HashMap<>();

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
        // 结佣 100000（FIRST_HAND）折算后 90240
        input.lockedByEmp = Map.of(e.getEmployeeId(), List.of(item(e.getEmployeeId(), new BigDecimal("100000"), "FIRST_HAND")));
        input.newsignByEmp = new HashMap<>();
        input.deptNewSignTotal = new HashMap<>();
        input.snapshot = buildSnapshot();
        input.manualIncome = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(e.getEmployeeId(), 1);
        input.perfGrade = Map.of(e.getEmployeeId(), "A");
        input.qualifiedApprenticeCount = new HashMap<>();
        input.apprenticeCommission = new HashMap<>();

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
        input.manualIncome = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(dir.getEmployeeId(), 1);
        input.perfGrade = Map.of(dir.getEmployeeId(), "A");
        input.qualifiedApprenticeCount = new HashMap<>();
        input.apprenticeCommission = new HashMap<>();

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
        input.manualIncome = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(e.getEmployeeId(), 1);
        input.perfGrade = Map.of(e.getEmployeeId(), "B");
        input.qualifiedApprenticeCount = new HashMap<>();
        input.apprenticeCommission = new HashMap<>();

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
        input.manualIncome = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(mgr.getEmployeeId(), 1);
        input.perfGrade = Map.of(mgr.getEmployeeId(), "A");
        input.qualifiedApprenticeCount = new HashMap<>();
        input.apprenticeCommission = new HashMap<>();

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
        input.manualIncome = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(dir.getEmployeeId(), 1);
        input.perfGrade = Map.of(dir.getEmployeeId(), "A");
        input.qualifiedApprenticeCount = new HashMap<>();
        input.apprenticeCommission = new HashMap<>();

        PayrollDetail d = engine.calculate(input).get(0);
        // 门店提成 = (120000 - 1000) × 7% = 8330
        assertEquals(0, new BigDecimal("8330.00").compareTo(d.getStoreIncome()));
        // 落地字段对齐天街工资表总监 sheet
        assertEquals(0, new BigDecimal("120000.00").compareTo(d.getDeptNewSignTotal()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(d.getDeptEmployerSocialTotal()));
        // storeRate 固定 0（跳点档位在 directorStoreItems JSON 明细中，不落地到 storeRate）
        assertEquals(0, BigDecimal.ZERO.compareTo(d.getStoreRate()));
        // 全勤奖兜底 500（policy 未配 fullAttendance 时默认 500）
        assertEquals(0, new BigDecimal("500.00").compareTo(d.getFullAttendance()));
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
        input.manualIncome = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(dir.getEmployeeId(), 1);
        input.perfGrade = Map.of(dir.getEmployeeId(), "A");
        input.qualifiedApprenticeCount = new HashMap<>();
        input.apprenticeCommission = new HashMap<>();

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
        // 应发 = 底薪 + 门店提成 + 全勤 = 6000 + 16100 + 500 = 22600
        assertEquals(0, new BigDecimal("22600.00").compareTo(d.getGross()));
    }

    /** S-17: 全勤奖默认 500 — policy 未配 fullAttendance 时兜底 500 */
    @Test
    void testFullAttendanceDefault500() {
        EmployeeSnapshot dir = emp("D011", "D", "总监");
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.employees = List.of(dir);
        input.lockedByEmp = Map.of(dir.getEmployeeId(), new ArrayList<>());
        input.newsignByEmp = new HashMap<>();
        input.deptNewSignTotal = Map.of(1L, BigDecimal.ZERO);
        input.snapshot = buildSnapshot();
        input.manualIncome = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(dir.getEmployeeId(), 1);
        input.perfGrade = Map.of(dir.getEmployeeId(), "A");
        input.qualifiedApprenticeCount = new HashMap<>();
        input.apprenticeCommission = new HashMap<>();

        PayrollDetail d = engine.calculate(input).get(0);
        // buildSnapshot policy 无 fullAttendance → 兜底 500
        assertEquals(0, new BigDecimal("500.00").compareTo(d.getFullAttendance()));
    }

    /** S-12: 考勤扣款 — 迟到×20；无底薪旷工×50；有底薪旷工按 3 倍日工资；请假=天数×leaveFee(30)；导入金额兼容叠加 */
    @Test
    void testAttendanceFeeFromMetrics() {
        EmployeeSnapshot a2 = emp("A2010", "A2", "经纪人"); // 无底薪（baseSalary=0）
        EmployeeSnapshot a0 = emp("A0010", "A0", "经纪人"); // 底薪 4500
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.employees = List.of(a2, a0);
        input.lockedByEmp = Map.of(a2.getEmployeeId(), new ArrayList<>(), a0.getEmployeeId(), new ArrayList<>());
        input.newsignByEmp = new HashMap<>();
        input.deptNewSignTotal = new HashMap<>();
        input.snapshot = buildSnapshot();
        input.manualIncome = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = Map.of(a2.getEmployeeId(), 1, a0.getEmployeeId(), 1);
        input.perfGrade = Map.of(a2.getEmployeeId(), "A", a0.getEmployeeId(), "A");
        input.qualifiedApprenticeCount = new HashMap<>();
        input.apprenticeCommission = new HashMap<>();

        // A2（无底薪）：迟到 3 次 + 旷工 2 天 + 导入金额 10 → 3×20 + 2×50 + 10 = 170
        AttendanceMetricsDTO m2 = new AttendanceMetricsDTO();
        m2.setImportedFee(new BigDecimal("10"));
        m2.setLateCount(3);
        m2.setAbsentDays(new BigDecimal("2"));
        m2.setLeaveDays(BigDecimal.ZERO);
        // A0（底薪 4500，日工资 = 4500/21.75 = 206.896552）：
        //   迟到 1 次 20 + 旷工 1 天 3×206.896552 + 请假 1 天×leaveFee 30 = 670.689656 → 670.69
        AttendanceMetricsDTO m0 = new AttendanceMetricsDTO();
        m0.setImportedFee(BigDecimal.ZERO);
        m0.setLateCount(1);
        m0.setAbsentDays(BigDecimal.ONE);
        m0.setLeaveDays(BigDecimal.ONE);
        input.attendanceMetrics = Map.of(a2.getEmployeeId(), m2, a0.getEmployeeId(), m0);

        List<PayrollDetail> details = engine.calculate(input);
        PayrollDetail d2 = details.stream().filter(x -> x.getEmployeeId().equals(a2.getEmployeeId())).findFirst().orElseThrow();
        PayrollDetail d0 = details.stream().filter(x -> x.getEmployeeId().equals(a0.getEmployeeId())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("170.00").compareTo(d2.getAttendanceFee()));
        assertEquals(0, new BigDecimal("670.69").compareTo(d0.getAttendanceFee()));
    }
}
