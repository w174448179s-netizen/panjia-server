package com.panjia.performance.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.dto.PerformanceContractSummaryDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.dto.ReceivedAlignmentResultDTO;
import com.panjia.contracts.port.CommissionPerformanceQueryPort;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.ReversedReason;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.service.ReceivedAlignmentService;
import com.panjia.performance.service.ReverseService;
import com.panjia.performance.util.MoneyUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 业绩事实跨域查询适配器（panjia-performance 实现 contracts {@link CommissionPerformanceQueryPort}）。
 * <p>
 * 设计说明：CommissionPerformanceQueryPort 是跨域端口（定义在 panjia-contracts），
 * 实现方在数据归属域（panjia-performance）。依赖方向 commission → contracts ← performance，
 * 结佣域完全不知道业绩域的 mapper / entity，严禁直连 {@code pj_perf_*} 表（CI C3/C4）。
 * <p>
 * 口径：findActive* 只返回 ACTIVE；金额取 performance_amount 原样值；
 * getByFactId 不限状态（溯源需能看到已冲销事实）。
 */
@Service
@RequiredArgsConstructor
public class CommissionPerformanceAdapter implements CommissionPerformanceQueryPort {

    private final PerformanceFactMapper factMapper;
    private final ReceivedAlignmentService receivedAlignmentService;
    private final ReverseService reverseService;

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByDept(String period, Long deptId, String factType) {
        LambdaQueryWrapper<PerformanceFact> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PerformanceFact::getPeriod, period)
            .eq(deptId != null, PerformanceFact::getDeptId, deptId)
            .eq(FactType.fromCode(factType) != null, PerformanceFact::getFactType, FactType.fromCode(factType))
            .eq(PerformanceFact::getFactStatus, FactStatus.ACTIVE)
            .orderByAsc(PerformanceFact::getId);
        return toSummaries(factMapper.selectList(wrapper));
    }

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByEmployee(String period, Long employeeId, String factType) {
        LambdaQueryWrapper<PerformanceFact> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PerformanceFact::getPeriod, period)
            .eq(PerformanceFact::getEmployeeId, employeeId)
            .eq(FactType.fromCode(factType) != null, PerformanceFact::getFactType, FactType.fromCode(factType))
            .eq(PerformanceFact::getFactStatus, FactStatus.ACTIVE)
            .orderByAsc(PerformanceFact::getId);
        return toSummaries(factMapper.selectList(wrapper));
    }

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByFacts(java.util.Collection<Long> factIds) {
        if (factIds == null || factIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<PerformanceFactSummaryDTO> all = factMapper.selectFactSummariesByIds(factIds);
        List<PerformanceFactSummaryDTO> active = new ArrayList<>(all.size());
        for (PerformanceFactSummaryDTO dto : all) {
            if ("ACTIVE".equals(dto.getFactStatus())) {
                active.add(dto);
            }
        }
        return active;
    }

    @Override
    public PerformanceFactSummaryDTO getByFactId(Long factId) {
        if (factId == null) {
            return null;
        }
        List<PerformanceFactSummaryDTO> list = factMapper.selectFactSummariesByIds(Collections.singletonList(factId));
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByContract(String period, String contractNo, String factType) {
        return factMapper.selectActiveFactSummariesByContractNo(period, factType, contractNo);
    }

    @Override
    public List<PerformanceContractSummaryDTO> listContractSummaries(String period, Long deptId, String factType) {
        return factMapper.selectContractSummaries(period, factType, deptId);
    }

    @Override
    public ReceivedAlignmentResultDTO alignReceivedToExpected(String period, String contractNo, Long operatorId) {
        return receivedAlignmentService.align(period, contractNo, operatorId);
    }

    private List<PerformanceFactSummaryDTO> toSummaries(List<PerformanceFact> facts) {
        List<PerformanceFactSummaryDTO> list = new ArrayList<>(facts.size());
        for (PerformanceFact fact : facts) {
            list.add(toSummary(fact));
        }
        return list;
    }

    private PerformanceFactSummaryDTO toSummary(PerformanceFact fact) {
        PerformanceFactSummaryDTO dto = new PerformanceFactSummaryDTO();
        dto.setFactId(fact.getId());
        dto.setFactType(fact.getFactType() != null ? fact.getFactType().getCode() : null);
        dto.setFactStatus(fact.getFactStatus() != null ? fact.getFactStatus().getCode() : null);
        dto.setPeriod(fact.getPeriod());
        dto.setBusinessDate(fact.getBusinessDate());
        dto.setEmployeeId(fact.getEmployeeId());
        dto.setEmployeeCode(fact.getEmployeeExternalCode());
        dto.setDeptId(fact.getDeptId());
        dto.setBizType(fact.getBizType());
        dto.setRoleType(fact.getRoleType());
        dto.setAmount(fact.getPerformanceAmount());
        dto.setBatchId(fact.getBatchId());
        dto.setNormalizedRecordId(fact.getNormalizedRecordId());
        dto.setSourceKey(fact.getSourceKey());
        dto.setReceivedApplyId(fact.getReceivedApplyId());
        dto.setShareRatio(fact.getShareRatio());
        return dto;
    }

    // ==================== 结佣调整：事实变更（同 PerformanceAdjustServiceImpl 口径） ====================

    @Override
    public Map<Long, Long> adjustContractFactsAmount(String period, String contractNo, String factType,
                                                      BigDecimal targetAmount, Long operatorId, Long adjustId) {
        List<PerformanceFact> facts = factMapper.selectActiveFactsByContractNo(period, factType, contractNo);
        Map<Long, Long> mapping = new HashMap<>();
        if (facts == null || facts.isEmpty()) {
            return mapping;
        }
        BigDecimal total = facts.stream()
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal deltaTotal = MoneyUtil.round2(targetAmount.subtract(total));
        if (MoneyUtil.isZero(deltaTotal)) {
            return mapping;
        }
        List<BigDecimal> amounts = facts.stream()
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .toList();
        BigDecimal[] parts = allocateByAmount(amounts, deltaTotal);
        for (int i = 0; i < facts.size(); i++) {
            if (MoneyUtil.isZero(parts[i])) {
                continue;
            }
            PerformanceFact oldFact = facts.get(i);
            PerformanceFact newFact = copyFactBase(oldFact);
            newFact.setPerformanceAmount(MoneyUtil.round2(oldFact.getPerformanceAmount().add(parts[i])));
            newFact.setAdjustId(adjustId);
            PerformanceFact created = reverseService.supersede(oldFact.getId(), newFact, operatorId);
            mapping.put(oldFact.getId(), created.getId());
        }
        return mapping;
    }

    @Override
    public Long adjustFactAmount(Long factId, BigDecimal targetAmount, Long operatorId, Long adjustId) {
        PerformanceFact oldFact = factMapper.selectById(factId);
        if (oldFact == null) {
            return null;
        }
        PerformanceFact newFact = copyFactBase(oldFact);
        newFact.setPerformanceAmount(MoneyUtil.round2(targetAmount));
        newFact.setAdjustId(adjustId);
        PerformanceFact created = reverseService.supersede(oldFact.getId(), newFact, operatorId);
        return created.getId();
    }

    @Override
    public void voidFact(Long factId, Long operatorId, Long adjustId) {
        reverseService.reverseByAdjust(factId, adjustId, ReversedReason.MANUAL_ADJUST, operatorId);
    }

    @Override
    public Long transferFact(Long factId, Long targetDeptId, Long operatorId, Long adjustId) {
        PerformanceFact oldFact = factMapper.selectById(factId);
        if (oldFact == null) {
            return null;
        }
        PerformanceFact newFact = copyFactBase(oldFact);
        newFact.setDeptId(targetDeptId);
        newFact.setAdjustId(adjustId);
        PerformanceFact created = reverseService.supersede(oldFact.getId(), newFact, operatorId);
        return created.getId();
    }

    /** 按金额占比分摊差额（与 PerformanceAdjustServiceImpl.allocateByAmount 同口径）。 */
    private BigDecimal[] allocateByAmount(List<BigDecimal> amounts, BigDecimal deltaTotal) {
        BigDecimal total = amounts.stream()
            .map(a -> a == null ? BigDecimal.ZERO : a)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal[] parts = new BigDecimal[amounts.size()];
        if (total.signum() == 0) {
            BigDecimal even = amounts.isEmpty() ? BigDecimal.ZERO
                : MoneyUtil.round2(deltaTotal.divide(BigDecimal.valueOf(amounts.size()), 8, RoundingMode.HALF_UP));
            BigDecimal allocated = BigDecimal.ZERO;
            for (int i = 0; i < amounts.size(); i++) {
                parts[i] = even;
                allocated = allocated.add(even);
            }
            if (!amounts.isEmpty()) {
                parts[0] = MoneyUtil.round2(parts[0].add(deltaTotal.subtract(allocated)));
            }
            return parts;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        int largestIdx = 0;
        BigDecimal largestAbs = BigDecimal.ZERO;
        for (int i = 0; i < amounts.size(); i++) {
            BigDecimal abs = amounts.get(i).abs();
            if (abs.compareTo(largestAbs) > 0) {
                largestAbs = abs;
                largestIdx = i;
            }
            parts[i] = MoneyUtil.round2(deltaTotal.multiply(amounts.get(i)).divide(total, 8, RoundingMode.HALF_UP));
            allocated = allocated.add(parts[i]);
        }
        BigDecimal tail = MoneyUtil.round2(deltaTotal.subtract(allocated));
        parts[largestIdx] = MoneyUtil.round2(parts[largestIdx].add(tail));
        return parts;
    }

    /** 复制事实基础字段（batchId 置空，避免被批次 supersede 误冲销），与新签调整一致。 */
    private PerformanceFact copyFactBase(PerformanceFact oldFact) {
        PerformanceFact newFact = new PerformanceFact();
        newFact.setFactType(oldFact.getFactType());
        newFact.setPeriod(oldFact.getPeriod());
        newFact.setBusinessDate(oldFact.getBusinessDate());
        newFact.setBatchId(null);
        newFact.setNormalizedRecordId(oldFact.getNormalizedRecordId());
        newFact.setSourceKey(oldFact.getSourceKey());
        newFact.setBizType(oldFact.getBizType());
        newFact.setEmployeeId(oldFact.getEmployeeId());
        newFact.setEmployeeExternalCode(oldFact.getEmployeeExternalCode());
        newFact.setDeptId(oldFact.getDeptId());
        newFact.setRoleType(oldFact.getRoleType());
        newFact.setShareRatio(oldFact.getShareRatio());
        newFact.setPerformanceAmount(oldFact.getPerformanceAmount());
        newFact.setEffectiveDate(oldFact.getEffectiveDate() != null
            ? oldFact.getEffectiveDate() : oldFact.getBusinessDate());
        newFact.setFactStatus(FactStatus.ACTIVE);
        newFact.setSource(oldFact.getSource());
        return newFact;
    }
}
