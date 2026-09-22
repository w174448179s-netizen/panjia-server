package com.panjia.performance.service;

import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.dto.ReceivedAlignmentResultDTO;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.mapper.PerformanceFactMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.system.api.ConfigService;
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

    /** 配置项：实收应收差异容忍阈值（元），默认 1。 */
    private static final String CONFIG_DIFF_TOLERANCE = "panjia.commission.diff_tolerance";
    private static final BigDecimal DEFAULT_DIFF_TOLERANCE = BigDecimal.ONE;

    private final PerformanceFactMapper factMapper;
    private final ReverseService reverseService;
    private final ConfigService configService;

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
            // 仅比较 performance_amount：shareRatio 仅展示用，不作为对齐判定依据。
            // 差异在 1 元以内视为无差异，不做对齐，保持实收原样。
            if (withinTolerance(realFact.getPerformanceAmount(), expect.getPerformanceAmount())) {
                // 金额口径一致（含小额尾差）：不替换
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
        n.setOrderNo(realFact.getOrderNo());
        n.setContractNo(realFact.getContractNo());
        n.setPropertyAddress(realFact.getPropertyAddress());
        n.setFeeItem(realFact.getFeeItem());
        n.setEmployeeId(realFact.getEmployeeId());
        n.setEmployeeExternalCode(realFact.getEmployeeExternalCode());
        n.setDeptId(realFact.getDeptId());
        n.setRoleType(realFact.getRoleType());
        n.setRoleName(realFact.getRoleName());
        n.setShareRatio(expect.getShareRatio());
        n.setPerformanceAmount(expect.getPerformanceAmount());
        n.setEffectiveDate(realFact.getEffectiveDate() != null ? realFact.getEffectiveDate()
            : realFact.getBusinessDate());
        n.setFactStatus(FactStatus.ACTIVE);
        n.setSource(realFact.getSource());
        n.setReceivedApplyId(realFact.getReceivedApplyId());
        // 保留红冲/调整关联字段，避免溯源链断裂
        n.setReversalType(realFact.getReversalType());
        n.setRefundOfFactId(realFact.getRefundOfFactId());
        n.setAdjustId(realFact.getAdjustId());
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

    /** 实收/应收差异容忍阈值（元），从系统参数读取，缺失回退默认 1 元。 */
    private BigDecimal getDiffTolerance() {
        BigDecimal v = configService.getConfigDecimal(CONFIG_DIFF_TOLERANCE);
        return v == null ? DEFAULT_DIFF_TOLERANCE : v;
    }

    /** 金额容忍判定：|a - b| <= 容忍阈值（默认 1 元）视为一致，不触发对齐。阈值可在系统参数中调整。 */
    private boolean withinTolerance(BigDecimal a, BigDecimal b) {
        BigDecimal av = a == null ? BigDecimal.ZERO : a;
        BigDecimal bv = b == null ? BigDecimal.ZERO : b;
        return av.subtract(bv).abs().compareTo(getDiffTolerance()) <= 0;
    }
}
