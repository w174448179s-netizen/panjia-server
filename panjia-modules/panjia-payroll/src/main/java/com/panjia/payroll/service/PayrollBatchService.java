package com.panjia.payroll.service;

import com.panjia.contracts.dto.AttendanceApprovalStatusDTO;
import com.panjia.contracts.dto.CommissionItemDTO;
import com.panjia.contracts.dto.EmployeeMainDataDTO;
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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        input.snapshot = ruleService.parseSnapshot(ruleSnap.getSnapshotContent());

        // ====== 多期间规则快照构建（结佣提成统一按签约月取规则，2026-09-28 取消
        // settlement-rate-biz-types 按结算月分流） ======
        // 扫描结佣明细，收集需要的规则期间集合（与 engine.resolveCommissionRulePeriod 口径一致：
        // 签约月 → 业绩归属月兜底）
        Set<String> requiredPeriods = new HashSet<>();
        for (CommissionItemDTO it : lockedItems) {
            if (it.getBusinessDate() != null) {
                requiredPeriods.add(YearMonth.from(it.getBusinessDate()).toString());
            } else if (it.getPeriod() != null && !it.getPeriod().isBlank()) {
                requiredPeriods.add(it.getPeriod());
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

        // ====== 门店级聚合：新签 roll-up + 社保业绩扣款（标准×计缴人数）+ 新签与结佣差额 ======
        // 门店社保业绩扣款 = DEPT 政策 socialStandard（每人每月固定额）× 计缴参保人数
        //   计缴人数口径：非兼职 + 参保 + 个人社保比例>30%，按门店及下属组别合计
        // 新签与结佣差额 = 门店当月配置（pj_payroll_dept_monthly_config），未配置=0
        Map<Long, BigDecimal> deptDiffConfig = deptMonthlyConfigService.loadDiffForPeriod(period);

        // 收集相关部门（员工归属 + 新签归属 + 差额配置），统一归一到门店锚点
        Set<Long> relevantDeptIds = new HashSet<>();
        for (EmployeeSnapshot es : employees) {
            if (es.getDeptId() != null) relevantDeptIds.add(es.getDeptId());
        }
        relevantDeptIds.addAll(rawDeptNewSign.keySet());
        relevantDeptIds.addAll(deptDiffConfig.keySet());
        Map<Long, Long> deptStoreAnchor = relevantDeptIds.isEmpty()
            ? Map.of() : peopleQueryPort.findStoreAnchors(relevantDeptIds);
        input.deptStoreAnchor = deptStoreAnchor;

        Set<Long> storeIds = new HashSet<>(deptStoreAnchor.values());
        // 保留原始分组兜底（挂在无锚点部门的业绩仍可按原 deptId 取），门店键随后覆盖为 roll-up 值
        Map<Long, BigDecimal> storeNewSign = new HashMap<>(rawDeptNewSign);
        Map<Long, BigDecimal> storeSocial = new HashMap<>();
        Map<Long, BigDecimal> storeStandardMap = new HashMap<>();
        Map<Long, Integer> insuredCountMap = new HashMap<>();
        if (!storeIds.isEmpty()) {
            // 批量取门店及所有子孙组别（入口一次取数，避免循环查询）
            Map<Long, List<Long>> storeDescendants = peopleQueryPort.findDeptAndChildren(storeIds);
            // 门店计缴参保人数（员工按锚点门店归集）
            Map<Long, Integer> headcount = new HashMap<>();
            for (EmployeeSnapshot es : employees) {
                Long anchor = es.getDeptId() == null ? null : deptStoreAnchor.get(es.getDeptId());
                if (anchor != null && SalaryCalculationEngine.isInsuredHeadcount(es, input.snapshot)) {
                    headcount.merge(anchor, 1, Integer::sum);
                }
            }
            for (Long storeId : storeIds) {
                BigDecimal ns = BigDecimal.ZERO;
                for (Long descId : storeDescendants.getOrDefault(storeId, List.of(storeId))) {
                    BigDecimal v = rawDeptNewSign.get(descId);
                    if (v != null) ns = ns.add(v);
                }
                storeNewSign.put(storeId, ns);

                int count = headcount.getOrDefault(storeId, 0);
                BigDecimal standard = input.snapshot.deptSocialStandard(storeId);
                insuredCountMap.put(storeId, count);
                storeStandardMap.put(storeId, standard);
                storeSocial.put(storeId, standard.multiply(BigDecimal.valueOf(count)));
            }
        }
        input.deptNewSignTotal = storeNewSign;
        input.deptEmployerSocialTotal = storeSocial;
        input.deptSocialStandard = storeStandardMap;
        input.deptInsuredCount = insuredCountMap;
        input.deptDiffTotal = deptDiffConfig;

        // 总监管辖门店 deptId 列表（总监挂顶级根，取其直接子部门 = 门店级，
        // 门店级汇总已在上方统一完成，多门店按门店分别跳点算提成）
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
            Map<Long, List<Long>> deptDirectChildren = peopleQueryPort.findDirectChildren(directorDeptIds);
            for (EmployeeSnapshot dir : directors) {
                directorStoreDepts.put(dir.getEmployeeId(),
                    deptDirectChildren.getOrDefault(dir.getDeptId(), List.of()));
            }
        }
        input.directorStoreDepts = directorStoreDepts;

        // 部门展示名（总监门店提成明细导出按门店分行展示门店名）
        Set<Long> allDeptIds = new HashSet<>();
        allDeptIds.addAll(input.deptNewSignTotal.keySet());
        if (input.deptEmployerSocialTotal != null) {
            allDeptIds.addAll(input.deptEmployerSocialTotal.keySet());
        }
        allDeptIds.addAll(deptDiffConfig.keySet());
        allDeptIds.addAll(deptStoreAnchor.keySet());
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

        // 入职月数：EmployeeSnapshot 暂无 hireDate 字段，默认 1（后续由 people 域补齐）
        input.monthsEmployed = new HashMap<>();
        for (EmployeeSnapshot e : employees) {
            input.monthsEmployed.put(e.getEmployeeId(), 1);
        }

        // 招聘奖励：需要师徒关系（EmployeeSnapshot.mentorId）+ 结佣数据联合查询
        // 暂置默认值，后续迭代实现（需跨 people + commission 域联合查询）
        input.qualifiedApprenticeCount = new HashMap<>();
        input.apprenticeCommission = new HashMap<>();

        return input;
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
        return batchMapper.selectBatchIds(batchIds).stream()
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
        MyPayrollDetailVo vo = new MyPayrollDetailVo();
        vo.setBatch(batch);
        vo.setDetail(detail);
        vo.setEmployee(me);
        return vo;
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
