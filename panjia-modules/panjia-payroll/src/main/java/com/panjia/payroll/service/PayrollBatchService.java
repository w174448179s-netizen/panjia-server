package com.panjia.payroll.service;

import com.panjia.contracts.dto.AttendanceApprovalStatusDTO;
import com.panjia.contracts.dto.CommissionItemDTO;
import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.dto.ScoreFactsDTO;
import com.panjia.contracts.event.PayrollLockedEvent;
import com.panjia.contracts.port.CommissionQueryPort;
import com.panjia.contracts.port.CommissionPerformanceQueryPort;
import com.panjia.contracts.port.ConversionFactorPort;
import com.panjia.contracts.port.EmployeeMainDataQueryPort;
import com.panjia.contracts.port.PeopleAttendanceApprovalQueryPort;
import com.panjia.contracts.port.PeopleAttendanceMetricsQueryPort;
import com.panjia.contracts.port.PeopleQueryPort;
import com.panjia.contracts.port.PeopleScoreApprovalQueryPort;
import com.panjia.contracts.port.PeopleScoreQueryPort;
import com.panjia.contracts.port.PeriodCloseQueryPort;
import com.panjia.contracts.dto.ScoreApprovalStatusDTO;
import com.panjia.contracts.snapshot.EmployeeSnapshot;
import com.panjia.payroll.domain.BatchStatus;
import com.panjia.payroll.domain.ManualItemType;
import com.panjia.payroll.domain.PayrollBatch;
import com.panjia.payroll.domain.PayrollDetail;
import com.panjia.payroll.domain.PayrollEmployeeSnapshot;
import com.panjia.payroll.domain.RuleSnapshot;
import com.panjia.payroll.domain.vo.MyPayrollDetailVo;
import com.panjia.payroll.dto.RateAdjustItem;
import com.panjia.payroll.mapper.PayrollBatchMapper;
import com.panjia.payroll.mapper.PayrollDetailMapper;
import com.panjia.payroll.mapper.PayrollEmployeeSnapshotMapper;
import com.panjia.payroll.util.MoneyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.port.ApprovalAction;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ApprovalStartCmd;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工资批次服务（生命周期 + 算薪编排）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayrollBatchService {

    private final PayrollBatchMapper batchMapper;
    private final PayrollDetailMapper detailMapper;
    private final PayrollEmployeeSnapshotMapper empSnapshotMapper;
    private final PeopleQueryPort peopleQueryPort;
    private final CommissionQueryPort commissionQueryPort;
    private final PeriodCloseQueryPort periodCloseQueryPort;
    /** 考勤月度指标取数源：取自 {@code pj_people_attendance}（员工域权威源）。
     * 历史曾通过 {@code ImportNormalizedRecordQueryPort} 从归一表取，人事/总监手工
     * 调整后归一表不同步导致算薪失真，现已删除该路径。 */
    private final PeopleAttendanceMetricsQueryPort attendanceMetricsPort;
    private final RuleService ruleService;
    private final SalaryCalculationEngine engine;
    private final ManualItemService manualItemService;
    private final ApplicationEventPublisher eventPublisher;
    private final ApprovalPort approvalPort;
    private final EmployeeMainDataQueryPort employeeMainDataQueryPort;
    private final ConversionFactorPort conversionFactorPort;
    private final PeopleAttendanceApprovalQueryPort attendanceApprovalQueryPort;
    private final PeopleScoreApprovalQueryPort scoreApprovalQueryPort;
    private final PeopleScoreQueryPort scoreQueryPort;
    private final IRateAdjustService rateAdjustService;
    private final DeptMonthlyConfigService deptMonthlyConfigService;
    private final CommissionPerformanceQueryPort commissionPerformanceQueryPort;
    private final PromotionService promotionService;

    // ==================== 创建 ====================

    @Transactional(rollbackFor = Exception.class)
    public PayrollBatch createBatch(String period, String deptScope, Long operatorId) {
        // 卡点：当月考勤须总监审批通过（无考勤数据期间不卡）
        AttendanceApprovalStatusDTO approvalStatus = attendanceApprovalQueryPort.getApprovalStatus(period);
        if (!approvalStatus.isApproved()) {
            throw new ServiceException("当月考勤（" + period + "）未经总监审批通过，暂不能进入算薪");
        }
        // 卡点：当月积分须总监审批通过（无积分数据期间不卡，绩效等级默认 A 不扣点）
        ScoreApprovalStatusDTO scoreApprovalStatus = scoreApprovalQueryPort.getApprovalStatus(period);
        if (!scoreApprovalStatus.isApproved()) {
            throw new ServiceException("当月积分（" + period + "）未经总监审批通过，暂不能进入算薪");
        }
        // 唯一性
        Long exist = batchMapper.selectCount(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PayrollBatch>()
                .eq(PayrollBatch::getPeriod, period)
                .eq(PayrollBatch::getDeptScope, deptScope == null ? "ALL" : deptScope));
        if (exist != null && exist > 0) {
            throw new ServiceException("该归属月已存在工资批次：" + period);
        }
        PayrollBatch batch = new PayrollBatch();
        batch.setPeriod(period);
        batch.setDeptScope(deptScope == null ? "ALL" : deptScope);
        batch.setStatus(BatchStatus.DRAFT);
        batch.setEmployeeCount(0);
        batch.setGrossTotal(BigDecimal.ZERO);
        batch.setDeductTotal(BigDecimal.ZERO);
        batch.setTaxTotal(BigDecimal.ZERO);
        batch.setNetTotal(BigDecimal.ZERO);
        batch.setAttempt(0);
        batch.setOperatorId(operatorId);
        batchMapper.insert(batch);
        log.info("[薪酬] 创建批次：id={}, period={}", batch.getId(), period);
        return batch;
    }

    // ==================== 算薪 ====================

    @Transactional(rollbackFor = Exception.class)
    public PayrollBatch calculate(Long batchId, Long operatorId) {
        // 行锁串行化：并发连点/重试时后到请求阻塞重读，状态已翻转而快速失败，不再并发重算
        PayrollBatch batch = batchMapper.selectByIdForUpdate(batchId);
        if (batch == null) throw new ServiceException("批次不存在");
        batch.assertCanCalculate();

        batch.setStatus(BatchStatus.CALCULATING);
        batch.setOperatorId(operatorId);
        batchMapper.updateById(batch);

        try {
            // 1. 取结佣 + 新签事实
            String period = batch.getPeriod();
            List<CommissionItemDTO> lockedItems = commissionQueryPort.findLocked(period, null);
            List<CommissionItemDTO> newsignItems = commissionQueryPort.findNewSignByDept(period, null);
            LocalDate pointInMonth = YearMonth.parse(period).atDay(1);

            // 收集员工 ID：结佣 + 新签
            Set<Long> empIds = new HashSet<>();
            for (CommissionItemDTO it : lockedItems) if (it.getEmployeeId() != null) empIds.add(it.getEmployeeId());
            for (CommissionItemDTO it : newsignItems) if (it.getEmployeeId() != null) empIds.add(it.getEmployeeId());

            // 名单扩展①：当期手工项涉及的员工（纯扣款/奖金人员无结佣也需算薪，
            // 否则无业绩但有扣款/奖励的人会整月漏算——2026-08 实测漏 14/59 人）
            empIds.addAll(manualItemService.loadApprovedForPeriod(period).keySet());

            // 名单扩展①+：当期提成点调整生效的员工（无业绩仅有扣点的人也需入名单留痕）
            Map<Long, List<RateAdjustItem>> rateAdjustItems = rateAdjustService.loadEffectiveForPeriod(period);
            empIds.addAll(rateAdjustItems.keySet());

            // 名单扩展②（基线）：当月全体在职（ACTIVE/PARTTIME）且持有效职级事实的员工。
            // 无底薪/保底职级（A1~A5/C1~C3 等）零业绩时也必须入名单——否则其个人社保
            // 代扣/考勤等当月应扣项会整月漏算（2026-08 实测漏 12/63 人，如何方方）。
            // 挂靠贝壳新签空经纪人行的虚拟角色人无 LEVEL 事实，天然不在此列。
            empIds.addAll(peopleQueryPort.findActiveEmployeeIdsWithLevel(pointInMonth));

            // 名单扩展③：当期有员工级政策覆盖的人（个人社保/公积金固定额等），
            // 仅有代扣类政策覆盖的员工同样需要进名单完成当月结算
            Set<String> policyCodes = ruleService.employeePolicyScopeKeys();
            if (!policyCodes.isEmpty()) {
                empIds.addAll(peopleQueryPort.findEmployeeIdsByCodes(policyCodes).values());
            }

            if (empIds.isEmpty()) {
                batch.setStatus(BatchStatus.CALCULATED);
                batch.setEmployeeCount(0);
                batch.setAttempt(batch.getAttempt() + 1);
                batchMapper.updateById(batch);
                log.warn("[薪酬] 批次 {} 无任何业绩数据", period);
                return batch;
            }

            // 2. 取员工快照
            Map<Long, EmployeeSnapshot> empMap = peopleQueryPort.getEmployeeSnapshots(empIds, pointInMonth);
            if (empMap.isEmpty()) {
                throw new ServiceException("未获取到员工快照，请确认员工数据已导入");
            }
            List<EmployeeSnapshot> employees = new ArrayList<>(empMap.values());

            // 3. 冻结规则快照
            RuleSnapshot ruleSnap = ruleService.freezeSnapshot(batchId, period);
            batch.setRuleSnapshotId(ruleSnap.getId());
            batchMapper.updateById(batch);

            // 持久化员工快照（重复算薪先删旧）
            empSnapshotMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PayrollEmployeeSnapshot>()
                .eq(PayrollEmployeeSnapshot::getBatchId, batchId));
            for (EmployeeSnapshot es : employees) {
                PayrollEmployeeSnapshot pes = new PayrollEmployeeSnapshot();
                pes.setBatchId(batchId);
                pes.setEmployeeId(es.getEmployeeId());
                pes.setSnapshotDate(pointInMonth);
                pes.setSnapshotContent(toJson(es));
                empSnapshotMapper.insert(pes);
            }

            // 4. 组装算薪输入
            SalaryCalculationEngine.CalcInput input = buildCalcInput(period, employees, lockedItems, newsignItems, ruleSnap, empMap, rateAdjustItems);

            // 4.5 职级自动晋升/降级判定：必须在引擎计算之前——新职级一律次月 1 日生效，
            // 本次算薪仍按旧职级，故写事实不影响下方引擎取数（员工快照已按批次月切片冻结）。
            // 判定异常不阻断算薪主流程（留 error 日志人工介入，事实写入幂等可重算补齐）
            try {
                List<PromotionService.PromotionChange> promotions =
                    promotionService.checkAndApply(batchId, period, employees, operatorId);
                if (!promotions.isEmpty()) {
                    log.info("[薪酬] 批次 {} 职级变动 {} 人：{}", period, promotions.size(),
                        promotions.stream()
                            .map(p -> p.employeeCode() + " " + p.fromLevel() + "→" + p.toLevel()
                                + "(" + p.effectiveDate() + "生效, 累计业绩" + p.cumulativeAmount() + ")")
                            .collect(Collectors.joining("；")));
                }
            } catch (Exception e) {
                log.error("[薪酬] 批次 {} 职级晋升判定异常，跳过（不影响本次算薪）", period, e);
            }

            // 5. 执行算薪
            List<PayrollDetail> details = engine.calculate(input);

            // 6. 清理旧明细 + 写入新明细
            detailMapper.deleteByBatchId(batchId);
            for (PayrollDetail d : details) {
                d.setBatchId(batchId);
                d.setPeriod(period);
                d.setRuleSnapshotId(ruleSnap.getId());
                detailMapper.insert(d);
            }

            // 7. 汇总批次
            BigDecimal grossTotal = BigDecimal.ZERO, deductTotal = BigDecimal.ZERO,
                taxTotal = BigDecimal.ZERO, netTotal = BigDecimal.ZERO;
            for (PayrollDetail d : details) {
                grossTotal = grossTotal.add(MoneyUtil.nvl(d.getGross()));
                deductTotal = deductTotal.add(MoneyUtil.nvl(d.getDeduct()));
                taxTotal = taxTotal.add(MoneyUtil.nvl(d.getTax()));
                netTotal = netTotal.add(MoneyUtil.nvl(d.getNet()));
            }
            batch.setStatus(BatchStatus.CALCULATED);
            batch.setEmployeeCount(details.size());
            batch.setGrossTotal(MoneyUtil.round2(grossTotal));
            batch.setDeductTotal(MoneyUtil.round2(deductTotal));
            batch.setTaxTotal(MoneyUtil.round2(taxTotal));
            batch.setNetTotal(MoneyUtil.round2(netTotal));
            batch.setAttempt(batch.getAttempt() + 1);
            batchMapper.updateById(batch);

            log.info("[薪酬] 批次 {} 算薪完成：人数={}, 应发={}, 实发={}", period, details.size(), grossTotal, netTotal);
            return batch;
        } catch (Exception e) {
            batch.setStatus(BatchStatus.FAILED);
            batchMapper.updateById(batch);
            throw e;
        }
    }

    private SalaryCalculationEngine.CalcInput buildCalcInput(String period, List<EmployeeSnapshot> employees,
                                                              List<CommissionItemDTO> lockedItems,
                                                              List<CommissionItemDTO> newsignItems,
                                                              RuleSnapshot ruleSnap,
                                                              Map<Long, EmployeeSnapshot> empMap,
                                                              Map<Long, List<RateAdjustItem>> rateAdjustItems) {
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.employees = employees;
        input.period = period;
        input.snapshot = ruleService.parseSnapshot(ruleSnap.getSnapshotContent());

        // ====== 多期间规则快照构建（结佣提成统一按新签导入月取规则，2026-10-17 改） ======
        // 新签导入月 = 该笔结佣对应新签业绩事实的归属月（非签约日所在月、非结佣导入月）。
        // 先批量加载结佣明细绑定的事实（factId → 事实摘要）：
        //   · 绑新签事实（PERF_EXPECT，正常审批流/新签调整）→ period 即新签导入月；
        //   · 绑实收明细（PERF_REAL，历史导入/历史单）→ 按订单号+员工（退化合同号+员工）
        //     匹配 ACTIVE 新签事实，取最早归属月；未匹配到新签事实时兜底结佣导入月。
        Set<Long> factIds = lockedItems.stream()
            .map(CommissionItemDTO::getPerformanceFactId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        // 新签明细同样按新签导入月回溯（2026-10-18 改，经纪人新签提成与结佣提成同口径）：
        // 新签事实映射与结佣共用一套 factPeriodMap
        newsignItems.stream()
            .map(CommissionItemDTO::getPerformanceFactId)
            .filter(Objects::nonNull)
            .forEach(factIds::add);
        Map<Long, String> factPeriodMap = new HashMap<>();
        if (!factIds.isEmpty()) {
            List<PerformanceFactSummaryDTO> facts = commissionPerformanceQueryPort.findActiveByFacts(factIds);
            List<PerformanceFactSummaryDTO> realFacts = new ArrayList<>();
            for (PerformanceFactSummaryDTO f : facts) {
                if ("PERF_REAL".equals(f.getFactType())) {
                    realFacts.add(f);
                } else {
                    factPeriodMap.put(f.getFactId(), f.getPeriod());
                }
            }
            if (!realFacts.isEmpty()) {
                factPeriodMap.putAll(commissionPerformanceQueryPort.findNewSignPeriodsByRealFacts(realFacts));
                for (PerformanceFactSummaryDTO f : realFacts) {
                    factPeriodMap.putIfAbsent(f.getFactId(), f.getPeriod());
                }
            }
        }
        input.factPeriodMap = factPeriodMap;

        Set<String> requiredPeriods = new HashSet<>();
        // 结佣与新签明细均按新签导入月归集规则期间（2026-10-18 改）
        for (List<CommissionItemDTO> items : List.of(lockedItems, newsignItems)) {
            for (CommissionItemDTO it : items) {
                if (it.getPerformanceFactId() != null) {
                    String factPeriod = factPeriodMap.get(it.getPerformanceFactId());
                    if (factPeriod != null) {
                        requiredPeriods.add(factPeriod);
                    }
                }
            }
        }
        // 算薪期间本身也加入（主快照兜底）
        requiredPeriods.add(period);

        // 批量构建多期快照（每个期间取该月首日作为规则生效参考日期）
        Map<String, RuleService.ParsedSnapshot> periodSnapshots = new HashMap<>();
        for (String rp : requiredPeriods) {
            try {
                LocalDate refDate = YearMonth.parse(rp).atDay(1);
                periodSnapshots.put(rp, ruleService.buildSnapshotForDate(refDate));
            } catch (Exception e) {
                log.warn("[薪酬] 构建规则快照失败：period={}，回退主快照", rp, e);
            }
        }
        input.periodSnapshots = periodSnapshots;
        // ====== 多期间规则快照构建 end ======

        // ====== 多期间历史切片（结佣提成按新签导入月取职级/绩效/师徒，2026-10-17 改） ======
        // 员工升职后算薪月职级 ≠ 新签月职级，需按新签导入月取当时的 LEVEL 事实切片；
        // 主期间（算薪月）的职级/师徒即员工快照当前值，无需重复查询。
        Set<Long> commissionEmpIds = lockedItems.stream()
            .map(CommissionItemDTO::getEmployeeId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        // 新签提成同样按新签导入月取历史切片（2026-10-18 改）：切片员工范围含新签明细涉及员工
        newsignItems.stream()
            .map(CommissionItemDTO::getEmployeeId)
            .filter(Objects::nonNull)
            .forEach(commissionEmpIds::add);
        Map<String, Map<Long, String>> periodLevelMap = new HashMap<>();
        Map<String, Map<Long, ScoreFactsDTO>> periodScoreMap = new HashMap<>();
        Map<String, Map<Long, Integer>> periodApprenticeMap = new HashMap<>();
        Map<String, Map<Long, java.util.List<RateAdjustItem>>> periodAdjustMap = new HashMap<>();
        for (String rp : requiredPeriods) {
            if (rp.equals(period) || commissionEmpIds.isEmpty()) {
                continue;
            }
            try {
                LocalDate refDate = YearMonth.parse(rp).atDay(1);
                // 一次切片同时取历史职级（LEVEL 事实）与历史师徒关系（MENTOR 事实，按生效区间）
                Map<Long, String> levelByEmp = new HashMap<>();
                Map<Long, Integer> apprenticeByMentor = new HashMap<>();
                peopleQueryPort.getEmployeeSnapshots(commissionEmpIds, refDate)
                    .forEach((empId, es) -> {
                        levelByEmp.put(empId, es.getLevelCode());
                        if (es.getMentorId() != null) {
                            apprenticeByMentor.merge(es.getMentorId(), 1, Integer::sum);
                        }
                    });
                periodLevelMap.put(rp, levelByEmp);
                periodApprenticeMap.put(rp, apprenticeByMentor);
                // 历史绩效积分（该月无事实的员工结佣时按 0 分处理）
                periodScoreMap.put(rp, scoreQueryPort.scoreFacts(rp));
                // 该月当时生效的人工调整单（结佣调整按新签月口径；APPROVED 且 start_month ≤ rp ≤ end_month）
                periodAdjustMap.put(rp, rateAdjustService.loadEffectiveForPeriod(rp));
            } catch (Exception e) {
                log.warn("[薪酬] 构建历史切片失败：period={}，该期间结佣提成回退算薪月口径", rp, e);
            }
        }
        input.periodLevelMap = periodLevelMap;
        input.periodScoreMap = periodScoreMap;
        input.periodApprenticeMap = periodApprenticeMap;
        input.periodAdjustMap = periodAdjustMap;
        // ====== 多期间历史切片 end ======

        // 结佣按员工分组
        input.lockedByEmp = lockedItems.stream()
            .filter(it -> it.getEmployeeId() != null)
            .collect(Collectors.groupingBy(CommissionItemDTO::getEmployeeId));

        // 新签按员工分组
        input.newsignByEmp = newsignItems.stream()
            .filter(it -> it.getEmployeeId() != null)
            .collect(Collectors.groupingBy(CommissionItemDTO::getEmployeeId));

        // 新签按事实行原始 deptId 分组（折算后）；门店级 roll-up 在下方统一做
        Map<Long, BigDecimal> rawDeptNewSign = new HashMap<>();
        for (CommissionItemDTO it : newsignItems) {
            if (it.getDeptId() == null || it.getAmount() == null) continue;
            BigDecimal factor = input.snapshot.conversionFactor(it.getBizType());
            BigDecimal converted = it.getAmount().multiply(factor);
            rawDeptNewSign.merge(it.getDeptId(), converted, BigDecimal::add);
        }

        // ====== 组级聚合：新签 + 社保业绩扣款（标准×计缴人数）+ 新签与结佣差额 ======
        // 口径：店长/总监均按所在组别计算团队提成，不再向上归一到门店（2026-10-12 改）
        // 社保业绩扣款 = DEPT 政策 socialStandard（每人每月固定额）× 计缴参保人数
        //   计缴人数口径：非兼职 + 参保 + 个人社保比例>30%，按组别合计
        // 新签与结佣差额 = 组级当月配置（pj_payroll_dept_monthly_config），未配置=0
        Map<Long, BigDecimal> deptDiffConfig = deptMonthlyConfigService.loadDiffForPeriod(period);

        // 收集相关部门（员工归属 + 新签归属 + 差额配置），不再归一到门店锚点
        Set<Long> relevantDeptIds = new HashSet<>();
        for (EmployeeSnapshot es : employees) {
            if (es.getDeptId() != null) relevantDeptIds.add(es.getDeptId());
        }
        relevantDeptIds.addAll(rawDeptNewSign.keySet());
        relevantDeptIds.addAll(deptDiffConfig.keySet());

        // 组级新签 = 原始 deptId 分组（rawDeptNewSign 即为组级数据）
        Map<Long, BigDecimal> deptNewSign = new HashMap<>(rawDeptNewSign);
        Map<Long, BigDecimal> deptSocial = new HashMap<>();
        Map<Long, BigDecimal> deptStandardMap = new HashMap<>();
        Map<Long, Integer> deptInsuredCountMap = new HashMap<>();

        // 组级计缴参保人数：按员工所在组别直接统计
        Map<Long, Integer> headcountByDept = new HashMap<>();
        for (EmployeeSnapshot es : employees) {
            if (es.getDeptId() != null && SalaryCalculationEngine.isInsuredHeadcount(es, input.snapshot)) {
                headcountByDept.merge(es.getDeptId(), 1, Integer::sum);
            }
        }
        for (Map.Entry<Long, Integer> entry : headcountByDept.entrySet()) {
            Long deptId = entry.getKey();
            int count = entry.getValue();
            BigDecimal standard = input.snapshot.deptSocialStandard(deptId);
            deptInsuredCountMap.put(deptId, count);
            deptStandardMap.put(deptId, standard);
            deptSocial.put(deptId, standard.multiply(BigDecimal.valueOf(count)));
        }

        input.deptNewSignTotal = deptNewSign;
        input.deptEmployerSocialTotal = deptSocial;
        input.deptSocialStandard = deptStandardMap;
        input.deptInsuredCount = deptInsuredCountMap;
        input.deptDiffTotal = deptDiffConfig;

        // 总监管辖组 deptId 列表。部门层级为：总监根部门 → 门店 → 业务组，
        // 而新签/社保/差额等组级汇总均以「业务组 deptId」为 key，
        // 因此管辖列表需下钻到组级（直接子部门是门店，须再取一层子部门）。
        Map<Long, List<Long>> directorStoreDepts = new HashMap<>();
        Set<Long> directorDeptIds = new HashSet<>();
        List<EmployeeSnapshot> directors = new ArrayList<>();
        for (EmployeeSnapshot es : employees) {
            String pos = es.getPosition();
            String lvl = es.getLevelCode();
            boolean isDirector = (pos != null && pos.contains("总监")) || "D".equals(lvl);
            if (isDirector && es.getDeptId() != null) {
                directorDeptIds.add(es.getDeptId());
                directors.add(es);
            }
        }
        if (!directorDeptIds.isEmpty()) {
            Map<Long, List<Long>> storeLevel = peopleQueryPort.findDirectChildren(directorDeptIds);
            Set<Long> storeIds = storeLevel.values().stream()
                .flatMap(List::stream).collect(Collectors.toSet());
            Map<Long, List<Long>> groupLevel = storeIds.isEmpty()
                ? Map.of() : peopleQueryPort.findDirectChildren(storeIds);
            for (EmployeeSnapshot dir : directors) {
                List<Long> stores = storeLevel.getOrDefault(dir.getDeptId(), List.of());
                List<Long> groups = stores.stream()
                    .flatMap(sid -> groupLevel.getOrDefault(sid, List.<Long>of()).stream())
                    .collect(Collectors.toList());
                // 兜底：管辖根下无组级结构（如业绩直挂门店）时回退门店列表，避免管辖为空全为 0
                directorStoreDepts.put(dir.getEmployeeId(), groups.isEmpty() ? stores : groups);
            }
        }
        input.directorStoreDepts = directorStoreDepts;

        // 部门展示名（总监管辖组提成明细导出按组分行展示组名）
        Set<Long> allDeptIds = new HashSet<>();
        allDeptIds.addAll(input.deptNewSignTotal.keySet());
        if (input.deptEmployerSocialTotal != null) {
            allDeptIds.addAll(input.deptEmployerSocialTotal.keySet());
        }
        allDeptIds.addAll(deptDiffConfig.keySet());
        for (List<Long> children : directorStoreDepts.values()) {
            allDeptIds.addAll(children);
        }
        for (EmployeeSnapshot es : employees) {
            if (es.getDeptId() != null) allDeptIds.add(es.getDeptId());
        }
        input.deptNames = allDeptIds.isEmpty() ? Map.of() : peopleQueryPort.findDeptNames(allDeptIds);

        // 手工项：奖金 / 其他收入 / 其他支出 三类分流（工资明细分列展示，不再混算）
        input.bonusIncome = new HashMap<>();
        input.otherIncomeMap = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        manualItemService.loadApprovedForPeriod(period).forEach((empId, items) -> {
            for (var item : items) {
                var type = item.getItemType();
                if (type == ManualItemType.BONUS) {
                    input.bonusIncome.merge(empId, item.getAmount(), BigDecimal::add);
                } else if (type == ManualItemType.OTHER_INCOME) {
                    input.otherIncomeMap.merge(empId, item.getAmount(), BigDecimal::add);
                } else {
                    input.manualDeduct.merge(empId, item.getAmount(), BigDecimal::add);
                }
            }
        });

        // 考勤：月度指标（迟到次数/旷工天数/请假天数），引擎按 policy.attendance 规则计算扣款。
        // 取数源：pj_people_attendance（员工域考勤明细维护的权威源，人事/总监手工调整生效）。
        // 不能从 pj_normalized_record 取——导入归档后人事的手工修改不写回归一表，会算错。
        // AttendanceMetricsDTO.importedFee 始终为 0：考勤事实不含「导入扣款金额」，
        // 该字段语义属于月度业绩指标导入的其它扣减（如「7.1-121.31日何方方提成扣2%」），
        // 待 MonthlyMetricPort 接驳后由其提供。
        input.attendanceMetrics = attendanceMetricsPort.sumByPeriod(period);

        // 绩效事实：积分表按「出勤日平均积分」算 grade（A/B/C），按晚提交次数算扣款，
        // 但 port 只装 ScoreFactsDTO（总积分/出勤天数/晚提交次数，等级/扣款不在
        // port 预先算好）—— 引擎按 policy.points（gradeA/gradeB/deductA/B/C/penaltyFee）
        // 派生等级与扣款（参见 AttendanceMetricsDTO 同模式）。
        // 无积分数据的员工不在 Map 中，引擎默认 A 不扣点。
        input.scoreFacts = scoreQueryPort.scoreFacts(period);

        // 提成点调整：APPROVED 且 start_month ≤ period ≤ end_month 的人工调整单，
        // 引擎叠加到 finalRate（另含未参保自动扣点，见引擎），溯源写入 rate_adjust_json
        input.manualAdjustItems = rateAdjustItems;

        // 负工资结转：从上月工资明细中查询净发为负的记录
        String prevPeriod = YearMonth.parse(period).minusMonths(1).toString();
        input.negativeBalance = new HashMap<>();
        for (PayrollDetail prevDetail : detailMapper.selectNegativeNetByPeriod(prevPeriod)) {
            if (prevDetail.getEmployeeId() != null && prevDetail.getNet() != null) {
                input.negativeBalance.put(prevDetail.getEmployeeId(),
                    prevDetail.getNet().abs());
            }
        }

        // 累计个税 & 累计应纳税所得额：从当年（含之前月份）工资明细中按员工汇总
        String yearStart = period.substring(0, 4) + "-01";
        input.cumulativeTax = new HashMap<>();
        for (PayrollDetail td : detailMapper.selectCumulativeTax(yearStart, period)) {
            if (td.getEmployeeId() != null) {
                input.cumulativeTax.put(td.getEmployeeId(),
                    MoneyUtil.nvl(td.getTax()));
            }
        }
        input.cumulativeTaxable = new HashMap<>();
        for (PayrollDetail td : detailMapper.selectCumulativeTaxable(yearStart, period)) {
            if (td.getEmployeeId() != null) {
                // gross 字段在 SQL 中已聚合为 SUM(gross - deduct)，即累计应纳税所得额
                input.cumulativeTaxable.put(td.getEmployeeId(),
                    MoneyUtil.nvl(td.getGross()));
            }
        }

        // 个税累计月数：仅 tax.mode=ENGINE（系统内试算）时参与累计预扣；
        // 当前个税统一走外部导入（EXTERNAL 为默认模式），该值不参与实际计算，保留备用
        input.monthsEmployed = new HashMap<>();
        for (EmployeeSnapshot e : employees) {
            input.monthsEmployed.put(e.getEmployeeId(), 1);
        }

        // 招聘加点徒弟数：按算薪月师徒关系（EmployeeSnapshot.mentorId）统计在册徒弟数；
        // 历史月切片见 periodApprenticeMap（新签导入月的 MENTOR 事实）
        Map<Long, Integer> apprenticeByMentor = new HashMap<>();
        for (EmployeeSnapshot e : employees) {
            if (e.getMentorId() != null) {
                apprenticeByMentor.merge(e.getMentorId(), 1, Integer::sum);
            }
        }
        input.qualifiedApprenticeCount = apprenticeByMentor;
        // 招聘奖励（AMOUNT_RATIO）的徒弟结佣业绩合计由引擎在算薪时基于师徒关系+结佣明细预算

        return input;
    }

    // ==================== 个税回补（外部个税系统算税后导入） ====================

    /**
     * 个税回补导入：外部个税系统（自然人电子税务局扣缴端）算税后，按员工回填本月个税，
     * 覆盖 {@code tax}、重算 {@code net = gross - deduct - tax}、重汇总批次合计，并留痕
     * {@code tax_source = EXTERNAL}。
     * <p>
     * 时机：批次「已计算/审核中」（与重算相同的状态约束）——须在提交审批、锁定、发放前完成；
     * 重新算薪会整批重建明细（tax 置回引擎口径），导入需重做，属预期。
     * 幂等：重复导入按行覆盖；匹配不上的员工直接报错（整单回滚，不做部分导入）。
     *
     * @param taxByEmployeeId 员工ID(字符串，雪花 ID 前端以字符串传输) → 本月个税
     */
    @Transactional(rollbackFor = Exception.class)
    public PayrollBatch importTax(Long batchId, Map<String, BigDecimal> taxByEmployeeId, Long operatorId) {
        if (taxByEmployeeId == null || taxByEmployeeId.isEmpty()) {
            throw new ServiceException("导入数据为空");
        }
        // 行锁串行化，与算薪互斥；可算薪状态（CALCULATED/REVIEWING 等）才允许回补
        PayrollBatch batch = batchMapper.selectByIdForUpdate(batchId);
        if (batch == null) throw new ServiceException("批次不存在");
        batch.assertCanCalculate();

        Map<Long, PayrollDetail> byEmp = detailMapper.selectByBatchId(batchId).stream()
            .collect(Collectors.toMap(PayrollDetail::getEmployeeId, d -> d, (a, b) -> a));

        // 先整单校验再落库：任一行匹配失败/金额非法即报错回滚，避免出现半导入状态
        Map<Long, BigDecimal> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : taxByEmployeeId.entrySet()) {
            Long empId;
            try {
                empId = Long.valueOf(e.getKey());
            } catch (NumberFormatException nfe) {
                throw new ServiceException("非法员工ID：" + e.getKey());
            }
            PayrollDetail d = byEmp.get(empId);
            if (d == null) {
                throw new ServiceException("员工不在批次「" + batch.getPeriod() + "」中：employeeId=" + e.getKey());
            }
            BigDecimal tax = MoneyUtil.round2(e.getValue());
            if (tax.compareTo(BigDecimal.ZERO) < 0) {
                throw new ServiceException("个税不能为负：employeeId=" + e.getKey());
            }
            normalized.put(empId, tax);
        }

        for (Map.Entry<Long, BigDecimal> e : normalized.entrySet()) {
            PayrollDetail d = byEmp.get(e.getKey());
            d.setTax(e.getValue());
            d.setTaxSource("EXTERNAL");
            d.setNet(MoneyUtil.round2(
                MoneyUtil.nvl(d.getGross()).subtract(MoneyUtil.nvl(d.getDeduct())).subtract(e.getValue())));
            detailMapper.updateById(d);
        }

        // 重汇总批次合计（导入只影响 tax/net，gross/deduct 一并重算保持口径统一）
        BigDecimal grossTotal = BigDecimal.ZERO, deductTotal = BigDecimal.ZERO,
            taxTotal = BigDecimal.ZERO, netTotal = BigDecimal.ZERO;
        for (PayrollDetail d : detailMapper.selectByBatchId(batchId)) {
            grossTotal = grossTotal.add(MoneyUtil.nvl(d.getGross()));
            deductTotal = deductTotal.add(MoneyUtil.nvl(d.getDeduct()));
            taxTotal = taxTotal.add(MoneyUtil.nvl(d.getTax()));
            netTotal = netTotal.add(MoneyUtil.nvl(d.getNet()));
        }
        batch.setGrossTotal(MoneyUtil.round2(grossTotal));
        batch.setDeductTotal(MoneyUtil.round2(deductTotal));
        batch.setTaxTotal(MoneyUtil.round2(taxTotal));
        batch.setNetTotal(MoneyUtil.round2(netTotal));
        batch.setOperatorId(operatorId);
        batchMapper.updateById(batch);

        log.info("[薪酬] 批次 {} 个税回补完成：{} 人导入，个税合计={}, 实发合计={}",
            batchId, normalized.size(), taxTotal, netTotal);
        return batch;
    }

    // ==================== 状态流转（工作流驱动） ====================

    /**
     * 提交审核：CALCULATED → REVIEWING，并发起/推进 payroll_batch 流程。
     * <p>
     * 首次提交：发起流程并自动办理「提交算薪」节点（提交动作本身即该节点的办理，
     * HTTP 入口已有 payroll:batch:submit 权限码把关，与节点办理人集合一致）；
     * 总监驳回后重新提交：办理停留在 payroll_submit 的任务。
     * 后续 审核通过（payroll_lock 任务创建）/ 驳回（back 事件）/ 锁定（finish 事件）
     * 全部由 {@code PayrollBatchWorkflowListener} 按工作流事件推进，无业务直批路径。
     */
    @Transactional(rollbackFor = Exception.class)
    public PayrollBatch submit(Long batchId, Long operatorId) {
        PayrollBatch b = getOrThrow(batchId);
        b.assertCanSubmit();
        b.setStatus(BatchStatus.REVIEWING);
        b.setOperatorId(operatorId);
        batchMapper.updateById(b);

        if (b.getProcessInstanceId() == null || b.getProcessInstanceId().isBlank()) {
            startWorkflow(b, operatorId);
        } else {
            // 驳回后流程停在「提交算薪」节点：办理该任务重新提交
            Long taskId = approvalPort.currentTaskId(BizType.PAYROLL_BATCH, batchId);
            if (taskId == null) {
                throw new ServiceException("审批流程任务不存在，请联系管理员");
            }
            approvalPort.completeAsSys(BizType.PAYROLL_BATCH, batchId, ApprovalAction.PASS, "重新提交");
        }
        log.info("[薪酬] 批次已提交审核：id={}, period={}, operator={}", batchId, b.getPeriod(), operatorId);
        return b;
    }

    /**
     * 启动 payroll_batch 流程并自动办理「提交算薪」首节点。
     */
    private void startWorkflow(PayrollBatch batch, Long operatorId) {
        ApprovalStartCmd cmd = buildStartCmd(batch, operatorId);
        try {
            boolean ok = approvalPort.startAndCompleteFirst(BizType.PAYROLL_BATCH, batch.getId(), cmd);
            if (!ok) {
                throw new ServiceException("算薪审批流程发起失败");
            }
        } catch (Exception e) {
            log.error("[薪酬] 流程发起异常：batchId={}", batch.getId(), e);
            throw new ServiceException("算薪审批流程发起失败：{}", e.getMessage());
        }
        Long instanceId = approvalPort.instanceId(BizType.PAYROLL_BATCH, batch.getId());
        if (instanceId != null) {
            batch.setProcessInstanceId(String.valueOf(instanceId));
            batchMapper.updateById(batch);
        }
    }

    /**
     * 构建审批启动命令（业务编码/标题 + 办理人 + 流程变量），供适配器转译为引擎原生 StartProcessDTO + bizExt。
     */
    private ApprovalStartCmd buildStartCmd(PayrollBatch batch, Long operatorId) {
        ApprovalStartCmd cmd = ApprovalStartCmd.of(
            batch.getPeriod(),
            "算薪审批｜" + batch.getPeriod()
                + "｜范围" + ("ALL".equals(batch.getDeptScope()) ? "全部门店" : batch.getDeptScope())
                + "｜人数" + (batch.getEmployeeCount() == null ? 0 : batch.getEmployeeCount())
                + "｜实发" + (batch.getNetTotal() == null ? "0" : batch.getNetTotal()));
        cmd.setHandler(String.valueOf(operatorId));
        Map<String, Object> variables = new HashMap<>(2);
        variables.put("ignore", true);
        cmd.setVariables(variables);
        return cmd;
    }

    @Transactional(rollbackFor = Exception.class)
    public PayrollBatch pay(Long batchId, Long operatorId) {
        PayrollBatch b = getOrThrow(batchId);
        b.assertCanPay();
        b.setStatus(BatchStatus.PAID);
        b.setOperatorId(operatorId);
        batchMapper.updateById(b);
        return b;
    }

    // ==================== 解锁（期间反结账联动） ====================

    /**
     * 解锁指定期间所有已锁定的工资批次（方案 B：解封联动解锁）。
     * <p>
     * 由 {@code panjia-performance} 期间反结账（{@code PeriodReopenedEvent}）触发，
     * 与期间解封在同一事务内执行。解锁后：
     * <ul>
     *   <li>状态 LOCKED → CALCULATED：{@link BatchStatus#canCalculate()} 允许重算、
     *       {@link BatchStatus#canSubmit()} 允许重新走审批；</li>
     *   <li>清空 processInstanceId：工作流实例已随锁定节点 finish 结束，
     *       重新提交时需发起新流程（否则 currentTaskId 查不到任务）；</li>
     *   <li>清空 lockedBy / lockedAt：解除锁定人/时间留痕。</li>
     * </ul>
     * PAID（已发放）为资金终态，{@link PayrollBatch#assertCanUnlock()} 会拒绝。
     *
     * @param period     业绩期间 = 工资归属月 YYYY-MM
     * @param reason     反结账原因（透传留痕日志）
     * @param operatorId 解封操作人 ID
     * @return 实际解锁的批次数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int unlockByPeriod(String period, String reason, Long operatorId) {
        if (period == null || period.isBlank()) {
            throw new ServiceException("期间不能为空");
        }
        List<PayrollBatch> locked = batchMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PayrollBatch>()
                .eq(PayrollBatch::getPeriod, period)
                .eq(PayrollBatch::getStatus, BatchStatus.LOCKED));
        int count = 0;
        for (PayrollBatch b : locked) {
            b.assertCanUnlock();
            b.setStatus(BatchStatus.CALCULATED);
            b.setProcessInstanceId(null);
            b.setLockedBy(null);
            b.setLockedAt(null);
            b.setOperatorId(operatorId);
            batchMapper.updateById(b);
            count++;
            log.info("[薪酬反结账] 批次解锁：batchId={}, period={}, operator={}, reason={}",
                b.getId(), period, operatorId, reason);
        }
        if (count == 0) {
            log.info("[薪酬反结账] 期间 {} 无 LOCKED 批次，跳过解锁", period);
        }
        return count;
    }

    // ==================== 工作流回调（PayrollBatchWorkflowListener 调用） ====================

    /**
     * payroll_batch 流程实例级事件处理。
     * <ul>
     *   <li>finish（总监锁定节点办理完成）：APPROVED → LOCKED，落 locked_by/locked_at，
     *       发布 PayrollLockedEvent → performance 域自动封账对应业绩月（V4.2 §13.1）；</li>
     *   <li>back（总监审核驳回，退回「提交算薪」节点）：REVIEWING → CALCULATED；</li>
     *   <li>cancel/invalid/termination：退回 CALCULATED 并解除实例绑定，可重新发起。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleWorkflowEvent(Long batchId, String status, String handler, String message) {
        PayrollBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            log.warn("[薪酬工作流] 批次不存在，忽略：id={}, status={}", batchId, status);
            return;
        }
        Long handlerId = parseHandlerId(handler);
        switch (status == null ? "" : status) {
            case "finish" -> {
                if (batch.getStatus() == BatchStatus.LOCKED || batch.getStatus() == BatchStatus.PAID) {
                    return;
                }
                batch.setStatus(BatchStatus.LOCKED);
                batch.setLockedBy(handlerId != null ? handlerId : batch.getOperatorId());
                batch.setLockedAt(java.time.LocalDateTime.now());
                batchMapper.updateById(batch);

                List<PayrollDetail> details = detailMapper.selectByBatchId(batchId);
                List<Long> itemIds = details.stream().map(PayrollDetail::getId).toList();
                List<PayrollLockedEvent.DeptCostSummary> deptCosts = buildDeptCosts(details);
                PayrollLockedEvent event = new PayrollLockedEvent();
                event.setEventId(java.util.UUID.randomUUID().toString());
                event.setPeriod(batch.getPeriod());
                event.setBatchId(batchId);
                event.setLockedBy(batch.getLockedBy());
                event.setItemIds(itemIds);
                event.setDeptCosts(deptCosts);
                eventPublisher.publishEvent(event);
                log.info("[薪酬工作流] 批次已锁定：id={}, period={}, itemCount={}",
                    batchId, batch.getPeriod(), itemIds.size());
            }
            case "back" -> {
                if (batch.getStatus() != BatchStatus.REVIEWING && batch.getStatus() != BatchStatus.APPROVED) {
                    return;
                }
                batch.setStatus(BatchStatus.CALCULATED);
                batchMapper.updateById(batch);
                log.info("[薪酬工作流] 总监驳回，退回已计算：id={}, message={}", batchId, message);
            }
            case "cancel", "invalid", "termination" -> {
                batch.setStatus(BatchStatus.CALCULATED);
                batch.setProcessInstanceId(null);
                batchMapper.updateById(batch);
                log.info("[薪酬工作流] 流程终止，退回已计算：id={}, status={}", batchId, status);
            }
            default -> log.info("[薪酬工作流] 忽略状态：id={}, status={}", batchId, status);
        }
    }

    /**
     * 流程进入指定节点（任务创建事件）：推进批次审批态。
     * <ul>
     *   <li>payroll_review（总监审核任务创建）：→ REVIEWING。
     *       覆盖「驳回后从我的待办直接办理重新提交」的路径（业务提交入口已先行置 REVIEWING，幂等）；</li>
     *   <li>payroll_lock（总监锁定任务创建，即总监审核已通过）：→ APPROVED。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleTaskNodeEvent(Long batchId, String nodeCode) {
        PayrollBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            log.warn("[薪酬工作流] 批次不存在，忽略节点事件：id={}, node={}", batchId, nodeCode);
            return;
        }
        if ("payroll_review".equals(nodeCode)) {
            if (batch.getStatus() == BatchStatus.REVIEWING) {
                return;
            }
            batch.setStatus(BatchStatus.REVIEWING);
            batchMapper.updateById(batch);
        } else if ("payroll_lock".equals(nodeCode)) {
            if (batch.getStatus() == BatchStatus.APPROVED || batch.getStatus() == BatchStatus.LOCKED) {
                return;
            }
            batch.setStatus(BatchStatus.APPROVED);
            batchMapper.updateById(batch);
            log.info("[薪酬工作流] 总监审核通过，待锁定：id={}", batchId);
        }
    }

    private Long parseHandlerId(String handler) {
        if (handler == null || handler.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(handler.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 查询 ====================

    public PayrollBatch get(Long batchId) {
        return getOrThrow(batchId);
    }

    public List<PayrollBatch> list(String period) {
        var qw = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PayrollBatch>();
        if (period != null && !period.isBlank()) qw.eq(PayrollBatch::getPeriod, period);
        qw.orderByDesc(PayrollBatch::getCreateTime);
        return batchMapper.selectList(qw);
    }

    public List<PayrollDetail> listDetails(Long batchId) {
        return detailMapper.selectByBatchId(batchId);
    }

    // ==================== 本人工资查询（数据范围强制为登录人本人） ====================

    /**
     * 列出当前登录人有工资明细的批次（期间倒序）。
     * <p>
     * employeeId 只能由登录用户经 {@link EmployeeMainDataQueryPort#getByUserId} 解析，
     * 接口不接受任何员工参数；账号未关联员工档案时返回空列表（前端展示空态）。
     */
    public List<PayrollBatch> listMyBatches(Long userId) {
        EmployeeMainDataDTO me = employeeMainDataQueryPort.getByUserId(userId);
        if (me == null || me.getEmployeeId() == null) {
            return List.of();
        }
        List<Long> batchIds = detailMapper.selectByEmployeeId(me.getEmployeeId()).stream()
            .map(PayrollDetail::getBatchId)
            .distinct()
            .collect(Collectors.toList());
        if (batchIds.isEmpty()) {
            return List.of();
        }
        // 只列出对员工可见的批次：已发送工资条 或 已锁定/已发放（历史批次天然可见，兼容发送功能上线前数据）
        return batchMapper.selectBatchIds(batchIds).stream()
            .filter(PayrollBatch::visibleToEmployee)
            .sorted(Comparator.comparing(PayrollBatch::getPeriod).reversed())
            .collect(Collectors.toList());
    }

    /**
     * 查当前登录人在指定批次的工资明细 + 员工主数据（姓名/工号/门店名）。
     */
    public MyPayrollDetailVo getMyDetail(Long userId, Long batchId) {
        EmployeeMainDataDTO me = employeeMainDataQueryPort.getByUserId(userId);
        if (me == null || me.getEmployeeId() == null) {
            throw new ServiceException("当前账号未关联员工档案，无法查询工资，请联系人事绑定");
        }
        PayrollDetail detail = detailMapper.selectByBatchAndEmployee(batchId, me.getEmployeeId());
        if (detail == null) {
            throw new ServiceException("未找到您在该批次的工资明细");
        }
        PayrollBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new ServiceException("工资批次不存在");
        }
        // 未发送且未锁定的批次对员工不可见（防止员工猜测 batchId 越权查看未发布工资）
        if (!batch.visibleToEmployee()) {
            throw new ServiceException("该批次工资尚未发送，请等待人事发送工资条");
        }
        MyPayrollDetailVo vo = new MyPayrollDetailVo();
        vo.setBatch(batch);
        vo.setDetail(detail);
        vo.setEmployee(me);
        return vo;
    }

    /**
     * 发送工资条：员工端工资查询立即可见（留痕发送人与时间）。
     * <p>
     * 算薪完成（CALCULATED/REVIEWING/APPROVED）且未锁定才可发送；可重复发送刷新留痕。
     */
    @Transactional(rollbackFor = Exception.class)
    public PayrollBatch send(Long batchId, Long operatorId, String operatorName) {
        PayrollBatch b = getOrThrow(batchId);
        b.assertCanSend();
        b.setSendTime(LocalDateTime.now());
        b.setSendBy(operatorName != null ? operatorName : String.valueOf(operatorId));
        batchMapper.updateById(b);
        return b;
    }

    /**
     * 员工核对工资：纯留痕标记（写明细行 ack_time），不影响工资审批流。
     * <p>
     * 前置：批次已对员工可见；批次锁定（LOCKED/PAID）后不允许再核对。幂等：已核对直接返回。
     */
    @Transactional(rollbackFor = Exception.class)
    public PayrollDetail ackMyDetail(Long userId, Long batchId) {
        EmployeeMainDataDTO me = employeeMainDataQueryPort.getByUserId(userId);
        if (me == null || me.getEmployeeId() == null) {
            throw new ServiceException("当前账号未关联员工档案");
        }
        PayrollDetail detail = detailMapper.selectByBatchAndEmployee(batchId, me.getEmployeeId());
        if (detail == null) {
            throw new ServiceException("未找到您在该批次的工资明细");
        }
        PayrollBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new ServiceException("工资批次不存在");
        }
        if (!batch.visibleToEmployee()) {
            throw new ServiceException("该批次工资尚未发送，无法核对");
        }
        if (batch.getStatus() != null && batch.getStatus().isLocked()) {
            throw new ServiceException("该批次工资已锁定，无需核对");
        }
        if (detail.getAckTime() != null) {
            return detail;
        }
        detail.setAckTime(LocalDateTime.now());
        detailMapper.updateById(detail);
        return detail;
    }

    // ==================== 结佣追溯（工资构成 → 每笔结佣明细） ====================

    /**
     * 本人工资查询页：查当前登录人在指定期间的已审批结佣明细（含合同/房源/比例 enrichment）。
     * employeeId 由后端按登录态解析，不接受前端参数。
     */
    public List<CommissionItemDTO> listMyCommissionTrace(Long userId, String period) {
        EmployeeMainDataDTO me = employeeMainDataQueryPort.getByUserId(userId);
        if (me == null || me.getEmployeeId() == null) {
            return List.of();
        }
        return enrichConvertedAmounts(commissionQueryPort.findLockedByEmployee(period, me.getEmployeeId()));
    }

    /**
     * 组织工资明细页（总监/财务）：查指定员工在指定期间的已审批结佣明细（含合同/房源/比例 enrichment）。
     */
    public List<CommissionItemDTO> listCommissionTrace(String period, Long employeeId) {
        return enrichConvertedAmounts(commissionQueryPort.findLockedByEmployee(period, employeeId));
    }

    /**
     * 本人工资查询页（店长）：查所在门店团队成员的新签明细。
     * deptId 由后端按登录态解析员工档案得到，不接受前端参数。
     */
    public List<CommissionItemDTO> listMyTeamNewSign(Long userId, String period) {
        EmployeeMainDataDTO me = employeeMainDataQueryPort.getByUserId(userId);
        if (me == null || me.getDeptId() == null) {
            return List.of();
        }
        return enrichConvertedAmounts(commissionQueryPort.findNewSignByDept(period, me.getDeptId()));
    }

    /**
     * 组织工资明细页（总监/财务）：查指定门店团队成员的新签明细。
     */
    public List<CommissionItemDTO> listTeamNewSign(String period, Long deptId) {
        return enrichConvertedAmounts(commissionQueryPort.findNewSignByDept(period, deptId));
    }

    /**
     * 导出/展示用：查指定期间全部结佣明细（deptId=null 不限门店）。
     * <p>
     * 该期间无结佣申请单明细时（如历史工资 Excel 导入期间只写业绩事实、不建申请单），
     * 回退为 PERF_REAL 实收业绩事实透传，保证结佣业绩 tab 与导出 sheet 有数据。
     */
    public List<CommissionItemDTO> listAllCommissionForExport(String period) {
        List<CommissionItemDTO> locked = commissionQueryPort.findLocked(period, null);
        if (locked != null && !locked.isEmpty()) {
            return enrichConvertedAmounts(locked);
        }
        return enrichConvertedAmounts(commissionQueryPort.findRealFacts(period, null));
    }

    /**
     * 导出用：查指定期间全部新签明细（deptId=null 不限门店）。
     */
    public List<CommissionItemDTO> listAllNewSignForExport(String period) {
        return enrichConvertedAmounts(commissionQueryPort.findNewSignByDept(period, null));
    }

    /**
     * 为 CommissionItemDTO 列表批量填充 convertedAmount（amount × 当前生效折算因子）。
     * 比例统一经 {@link ConversionFactorPort} 取（工资明细页与业务明细页同口径）。
     */
    private List<CommissionItemDTO> enrichConvertedAmounts(List<CommissionItemDTO> items) {
        if (items == null || items.isEmpty()) return items;
        Set<String> bizTypes = new HashSet<>();
        for (CommissionItemDTO it : items) {
            bizTypes.add(it.getBizType());
        }
        Map<String, BigDecimal> factors = conversionFactorPort.factorsOf(bizTypes);
        for (CommissionItemDTO it : items) {
            it.setConvertedAmount(conversionFactorPort.convert(it.getAmount(), factors.get(it.getBizType())));
        }
        return items;
    }

    public RuleSnapshot getRuleSnapshot(Long batchId) {
        return ruleService.getSnapshot(batchId);
    }

    private PayrollBatch getOrThrow(Long id) {
        PayrollBatch b = batchMapper.selectById(id);
        if (b == null) throw new ServiceException("批次不存在");
        return b;
    }

    /**
     * 按门店汇总人工成本（PayrollLockedEvent.deptCosts）。
     * netPayTotal = 门店实发合计；employerSocialTotal = 公司承担社保合计。
     */
    private List<PayrollLockedEvent.DeptCostSummary> buildDeptCosts(List<PayrollDetail> details) {
        Map<Long, BigDecimal[]> agg = new LinkedHashMap<>(); // deptId -> [netSum, socialSum, count]
        for (PayrollDetail d : details) {
            if (d.getDeptId() == null) continue;
            BigDecimal[] slot = agg.computeIfAbsent(d.getDeptId(),
                k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            slot[0] = slot[0].add(d.getNet() == null ? BigDecimal.ZERO : d.getNet());
            slot[1] = slot[1].add(d.getEmployerSocial() == null ? BigDecimal.ZERO : d.getEmployerSocial());
            slot[2] = slot[2].add(BigDecimal.ONE);
        }
        List<PayrollLockedEvent.DeptCostSummary> result = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal[]> e : agg.entrySet()) {
            result.add(new PayrollLockedEvent.DeptCostSummary(
                e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2].intValue()));
        }
        return result;
    }

    private String toJson(Object o) {
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
