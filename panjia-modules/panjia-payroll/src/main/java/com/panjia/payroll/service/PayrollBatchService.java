package com.panjia.payroll.service;

import com.panjia.contracts.dto.CommissionItemDTO;
import com.panjia.contracts.port.CommissionQueryPort;
import com.panjia.contracts.port.PeopleQueryPort;
import com.panjia.contracts.port.PeriodCloseQueryPort;
import com.panjia.contracts.snapshot.EmployeeSnapshot;
import com.panjia.payroll.domain.BatchStatus;
import com.panjia.payroll.domain.PayrollBatch;
import com.panjia.payroll.domain.PayrollDetail;
import com.panjia.payroll.domain.PayrollEmployeeSnapshot;
import com.panjia.payroll.domain.RuleSnapshot;
import com.panjia.payroll.mapper.PayrollBatchMapper;
import com.panjia.payroll.mapper.PayrollDetailMapper;
import com.panjia.payroll.mapper.PayrollEmployeeSnapshotMapper;
import com.panjia.payroll.util.MoneyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
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
    private final RuleService ruleService;
    private final SalaryCalculationEngine engine;
    private final ManualItemService manualItemService;

    // ==================== 创建 ====================

    @Transactional(rollbackFor = Exception.class)
    public PayrollBatch createBatch(String period, String deptScope, Long operatorId) {
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
        PayrollBatch batch = batchMapper.selectById(batchId);
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

            // 收集员工 ID
            Set<Long> empIds = new HashSet<>();
            for (CommissionItemDTO it : lockedItems) if (it.getEmployeeId() != null) empIds.add(it.getEmployeeId());
            for (CommissionItemDTO it : newsignItems) if (it.getEmployeeId() != null) empIds.add(it.getEmployeeId());

            if (empIds.isEmpty()) {
                batch.setStatus(BatchStatus.CALCULATED);
                batch.setEmployeeCount(0);
                batch.setAttempt(batch.getAttempt() + 1);
                batchMapper.updateById(batch);
                log.warn("[薪酬] 批次 {} 无任何业绩数据", period);
                return batch;
            }

            // 2. 取员工快照
            LocalDate pointInMonth = YearMonth.parse(period).atDay(1);
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
            SalaryCalculationEngine.CalcInput input = buildCalcInput(period, employees, lockedItems, newsignItems, ruleSnap, empMap);

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
                                                              Map<Long, EmployeeSnapshot> empMap) {
        SalaryCalculationEngine.CalcInput input = new SalaryCalculationEngine.CalcInput();
        input.employees = employees;
        input.snapshot = ruleService.parseSnapshot(ruleSnap.getSnapshotContent());

        // 结佣按员工分组
        input.lockedByEmp = lockedItems.stream()
            .filter(it -> it.getEmployeeId() != null)
            .collect(Collectors.groupingBy(CommissionItemDTO::getEmployeeId));

        // 新签按员工分组
        input.newsignByEmp = newsignItems.stream()
            .filter(it -> it.getEmployeeId() != null)
            .collect(Collectors.groupingBy(CommissionItemDTO::getEmployeeId));

        // 门店新签合计（折算后）
        Map<Long, BigDecimal> deptNewSign = new HashMap<>();
        for (CommissionItemDTO it : newsignItems) {
            if (it.getDeptId() == null || it.getAmount() == null) continue;
            BigDecimal factor = input.snapshot.conversionFactor(it.getBizType());
            BigDecimal converted = it.getAmount().multiply(factor);
            deptNewSign.merge(it.getDeptId(), converted, BigDecimal::add);
        }
        input.deptNewSignTotal = deptNewSign;

        // 手工项
        input.manualIncome = new HashMap<>();
        input.manualDeduct = new HashMap<>();
        manualItemService.loadApprovedForPeriod(period).forEach((empId, items) -> {
            for (var item : items) {
                if (item.getItemType() != null && item.getItemType().isIncome()) {
                    input.manualIncome.merge(empId, item.getAmount(), BigDecimal::add);
                } else {
                    input.manualDeduct.merge(empId, item.getAmount(), BigDecimal::add);
                }
            }
        });

        // 负工资结转 / 累计个税（暂空，后续迭代）
        input.negativeBalance = new HashMap<>();
        input.cumulativeTax = new HashMap<>();
        input.cumulativeTaxable = new HashMap<>();
        input.monthsEmployed = new HashMap<>();
        for (EmployeeSnapshot e : employees) {
            input.monthsEmployed.put(e.getEmployeeId(), 1);
        }

        // 考勤/积分（暂无导入数据，置空）
        input.attendanceFee = new HashMap<>();
        input.pointsFee = new HashMap<>();
        input.perfGrade = new HashMap<>();
        for (EmployeeSnapshot e : employees) {
            input.perfGrade.put(e.getEmployeeId(), "A");
        }

        // 招聘奖励（暂空）
        input.qualifiedApprenticeCount = new HashMap<>();
        input.apprenticeCommission = new HashMap<>();

        return input;
    }

    // ==================== 状态流转 ====================

    @Transactional(rollbackFor = Exception.class)
    public PayrollBatch submit(Long batchId, Long operatorId) {
        PayrollBatch b = getOrThrow(batchId);
        b.assertCanSubmit();
        b.setStatus(BatchStatus.REVIEWING);
        b.setOperatorId(operatorId);
        batchMapper.updateById(b);
        return b;
    }

    @Transactional(rollbackFor = Exception.class)
    public PayrollBatch approve(Long batchId, Long operatorId) {
        PayrollBatch b = getOrThrow(batchId);
        b.assertCanApprove();
        b.setStatus(BatchStatus.APPROVED);
        b.setOperatorId(operatorId);
        batchMapper.updateById(b);
        return b;
    }

    @Transactional(rollbackFor = Exception.class)
    public PayrollBatch reject(Long batchId, Long operatorId) {
        PayrollBatch b = getOrThrow(batchId);
        b.assertCanReject();
        b.setStatus(BatchStatus.CALCULATED);
        b.setOperatorId(operatorId);
        batchMapper.updateById(b);
        return b;
    }

    @Transactional(rollbackFor = Exception.class)
    public PayrollBatch lock(Long batchId, Long operatorId) {
        PayrollBatch b = getOrThrow(batchId);
        b.assertCanLock();
        b.setStatus(BatchStatus.LOCKED);
        b.setLockedBy(operatorId);
        b.setLockedAt(java.time.LocalDateTime.now());
        b.setOperatorId(operatorId);
        batchMapper.updateById(b);
        return b;
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

    public RuleSnapshot getRuleSnapshot(Long batchId) {
        return ruleService.getSnapshot(batchId);
    }

    private PayrollBatch getOrThrow(Long id) {
        PayrollBatch b = batchMapper.selectById(id);
        if (b == null) throw new ServiceException("批次不存在");
        return b;
    }

    private String toJson(Object o) {
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
