package com.panjia.performance.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.port.CommissionPerformanceQueryPort;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.mapper.PerformanceFactMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByDept(String period, Long deptId, String factType) {
        LambdaQueryWrapper<PerformanceFact> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PerformanceFact::getPeriod, period)
            .eq(PerformanceFact::getDeptId, deptId)
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
        LambdaQueryWrapper<PerformanceFact> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(PerformanceFact::getId, factIds)
            .eq(PerformanceFact::getFactStatus, FactStatus.ACTIVE)
            .orderByAsc(PerformanceFact::getId);
        return toSummaries(factMapper.selectList(wrapper));
    }

    @Override
    public PerformanceFactSummaryDTO getByFactId(Long factId) {
        if (factId == null) {
            return null;
        }
        PerformanceFact fact = factMapper.selectById(factId);
        return fact == null ? null : toSummary(fact);
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
        return dto;
    }
}
