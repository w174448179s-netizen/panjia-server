package com.panjia.performance.service;

import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.dto.ReceivedAlignmentResultDTO;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.mapper.PerformanceFactMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实收对齐应收服务（§3.5）。
 * <p>
 * 结佣总监审批发现合同「实收 ≠ 应收」时，由结佣域经
 * {@code CommissionPerformanceQueryPort.alignReceivedToExpected} 触发本服务：
 * 同合同下每条 PERF_REAL 事实按对应（同 sourceKey）PERF_EXPECT 事实的金额口径
 * supersede 为新事实——合同总额与每人明细的实收都被改写为应收口径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceivedAlignmentService {

    private static final String FACT_TYPE_REAL = "PERF_REAL";
    private static final String FACT_TYPE_EXPECT = "PERF_EXPECT";

    private final PerformanceFactMapper factMapper;
    private final ReverseService reverseService;

    /**
     * 执行实收对齐应收。
     *
     * @param period     归属期间
     * @param contractNo 合同号
     * @param operatorId 操作人
     * @return 对齐结果（映射 + 对齐前后合计）
     */
    @Transactional(rollbackFor = Exception.class)
    public ReceivedAlignmentResultDTO align(String period, String contractNo, Long operatorId) {
        List<PerformanceFact> realFacts = factMapper.selectActiveFactsByContractNo(
            period, FACT_TYPE_REAL, contractNo);
        List<PerformanceFact> expectFacts = factMapper.selectActiveFactsByContractNo(
            period, FACT_TYPE_EXPECT, contractNo);

        // 同 sourceKey 的 REAL/EXPECT 一一对应（导入引擎一行双发）
        Map<String, PerformanceFact> expectBySourceKey = new LinkedHashMap<>();
        for (PerformanceFact expect : expectFacts) {
            expectBySourceKey.putIfAbsent(expect.getSourceKey(), expect);
        }

        BigDecimal before = sum(realFacts);
        BigDecimal expectedTotal = sum(expectFacts);

        ReceivedAlignmentResultDTO result = new ReceivedAlignmentResultDTO();
        result.setPeriod(period);
        result.setContractNo(contractNo);
        result.setReceivedTotalBefore(before);
        result.setExpectedTotal(expectedTotal);

        int aligned = 0;
        for (PerformanceFact realFact : realFacts) {
            PerformanceFact expect = expectBySourceKey.get(realFact.getSourceKey());
            if (expect == null) {
                // 无对应应收事实（实收多出行）：保持不变
                log.warn("[实收对齐] 实收事实无对应应收事实，保持不变：factId={}, sourceKey={}",
                    realFact.getId(), realFact.getSourceKey());
                continue;
            }
            if (eq(realFact.getPerformanceAmount(), expect.getPerformanceAmount())
                && eq(realFact.getShareRatio(), expect.getShareRatio())) {
                // 金额口径已一致：不替换
                continue;
            }
            PerformanceFact newFact = copyAsAligned(realFact, expect);
            PerformanceFact persisted = reverseService.supersede(realFact.getId(), newFact, operatorId);
            aligned++;

            ReceivedAlignmentResultDTO.Mapping mapping = new ReceivedAlignmentResultDTO.Mapping();
            mapping.setOldFactId(realFact.getId());
            mapping.setNewFact(toSummary(persisted));
            result.getMappings().add(mapping);
        }

        List<PerformanceFact> afterFacts = factMapper.selectActiveFactsByContractNo(
            period, FACT_TYPE_REAL, contractNo);
        result.setReceivedTotalAfter(sum(afterFacts));

        log.info("[实收对齐] 合同实收已对齐应收：period={}, contractNo={}, 对齐明细数={}, before={}, after={}, expect={}",
            period, contractNo, aligned, before, result.getReceivedTotalAfter(), expectedTotal);
        return result;
    }

    /** 以实收事实为底复制，金额/系数口径覆盖为应收事实值（保留实收的 receivedApplyId 关联）。 */
    private PerformanceFact copyAsAligned(PerformanceFact realFact, PerformanceFact expect) {
        PerformanceFact n = new PerformanceFact();
        n.setFactType(realFact.getFactType());
        n.setPeriod(realFact.getPeriod());
        n.setBusinessDate(realFact.getBusinessDate());
        n.setBatchId(realFact.getBatchId());
        n.setNormalizedRecordId(realFact.getNormalizedRecordId());
        n.setSourceKey(realFact.getSourceKey());
        n.setBizType(realFact.getBizType());
        n.setEmployeeId(realFact.getEmployeeId());
        n.setEmployeeExternalCode(realFact.getEmployeeExternalCode());
        n.setDeptId(realFact.getDeptId());
        n.setRoleType(realFact.getRoleType());
        n.setShareRatio(expect.getShareRatio());
        n.setPerformanceAmount(expect.getPerformanceAmount());
        n.setEffectiveDate(realFact.getEffectiveDate() != null ? realFact.getEffectiveDate()
            : realFact.getBusinessDate());
        n.setFactStatus(FactStatus.ACTIVE);
        n.setSource(realFact.getSource());
        n.setReceivedApplyId(realFact.getReceivedApplyId());
        return n;
    }

    private PerformanceFactSummaryDTO toSummary(PerformanceFact f) {
        PerformanceFactSummaryDTO dto = new PerformanceFactSummaryDTO();
        dto.setFactId(f.getId());
        dto.setFactType(f.getFactType() != null ? f.getFactType().getCode() : null);
        dto.setFactStatus(f.getFactStatus() != null ? f.getFactStatus().getCode() : null);
        dto.setPeriod(f.getPeriod());
        dto.setBusinessDate(f.getBusinessDate());
        dto.setEmployeeId(f.getEmployeeId());
        dto.setEmployeeCode(f.getEmployeeExternalCode());
        dto.setDeptId(f.getDeptId());
        dto.setBizType(f.getBizType());
        dto.setRoleType(f.getRoleType());
        dto.setAmount(f.getPerformanceAmount());
        dto.setBatchId(f.getBatchId());
        dto.setNormalizedRecordId(f.getNormalizedRecordId());
        dto.setSourceKey(f.getSourceKey());
        dto.setReceivedApplyId(f.getReceivedApplyId());
        return dto;
    }

    private BigDecimal sum(List<PerformanceFact> facts) {
        return facts.stream()
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean eq(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.compareTo(b) == 0;
    }
}
