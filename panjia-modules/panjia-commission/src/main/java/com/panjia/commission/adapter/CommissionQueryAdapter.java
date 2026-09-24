package com.panjia.commission.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.commission.domain.CommissionItem;
import com.panjia.commission.domain.ItemStatus;
import com.panjia.commission.mapper.CommissionItemMapper;
import com.panjia.contracts.dto.CommissionItemDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.port.CommissionPerformanceQueryPort;
import com.panjia.contracts.port.CommissionQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 结佣查询跨域适配器（panjia-commission 实现 contracts {@link CommissionQueryPort}，对 payroll 唯一出口）。
 * <p>
 * 依赖方向 payroll → contracts ← commission，payroll 禁直连 {@code pj_commission_*} 表。
 * <ul>
 *   <li>findLocked*：只返回 APPROVED 明细（审批锁定后才可进工资）；</li>
 *   <li>findNewSign*：业绩域 PERF_EXPECT 事实<b>原样透传</b>，本域不折算、不加工（CI C15 带 bizType）；</li>
 *   <li>findByApplication：全量含 REVERSED，仅供审计对账。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CommissionQueryAdapter implements CommissionQueryPort {

    /** 数据来源标识：结佣明细 */
    private static final String SOURCE_ITEM = "COMMISSION_ITEM";
    /** 数据来源标识：业绩事实只读透传 */
    private static final String SOURCE_FACT = "PERFORMANCE_FACT";

    /** 新签口径：应收业绩（只读透传） */
    private static final String FACT_TYPE_EXPECT = "PERF_EXPECT";

    /** 结佣口径：实收业绩（只读透传，用于无结佣申请单期间的历史数据回退） */
    private static final String FACT_TYPE_REAL = "PERF_REAL";

    private final CommissionItemMapper itemMapper;
    private final CommissionPerformanceQueryPort performanceQueryPort;

    @Override
    public List<CommissionItemDTO> findLocked(String period, Long deptId) {
        List<CommissionItem> items = itemMapper.selectList(new LambdaQueryWrapper<CommissionItem>()
                .eq(CommissionItem::getPeriod, period)
                .eq(deptId != null, CommissionItem::getDeptId, deptId)
                .eq(CommissionItem::getStatus, ItemStatus.APPROVED)
                .orderByAsc(CommissionItem::getId));
        return enrichWithFacts(items);
    }

    @Override
    public List<CommissionItemDTO> findLockedByEmployee(String period, Long employeeId) {
        List<CommissionItem> items = itemMapper.selectList(new LambdaQueryWrapper<CommissionItem>()
                .eq(CommissionItem::getPeriod, period)
                .eq(CommissionItem::getEmployeeId, employeeId)
                .eq(CommissionItem::getStatus, ItemStatus.APPROVED)
                .orderByAsc(CommissionItem::getId));
        return enrichWithFacts(items);
    }

    @Override
    public List<CommissionItemDTO> findNewSignByDept(String period, Long deptId) {
        List<PerformanceFactSummaryDTO> baseFacts = performanceQueryPort
            .findActiveByDept(period, deptId, FACT_TYPE_EXPECT);
        if (baseFacts.isEmpty()) return Collections.emptyList();
        List<Long> factIds = baseFacts.stream().map(PerformanceFactSummaryDTO::getFactId).toList();
        return performanceQueryPort.findActiveByFacts(factIds).stream().map(this::fromFact).toList();
    }

    @Override
    public List<CommissionItemDTO> findNewSignByEmployee(String period, Long employeeId) {
        List<PerformanceFactSummaryDTO> facts = performanceQueryPort
            .findActiveByEmployee(period, employeeId, FACT_TYPE_EXPECT);
        return facts.stream().map(this::fromFact).toList();
    }

    @Override
    public List<CommissionItemDTO> findRealFacts(String period, Long deptId) {
        List<PerformanceFactSummaryDTO> baseFacts = performanceQueryPort
            .findActiveByDept(period, deptId, FACT_TYPE_REAL);
        if (baseFacts.isEmpty()) return Collections.emptyList();
        List<Long> factIds = baseFacts.stream().map(PerformanceFactSummaryDTO::getFactId).toList();
        return performanceQueryPort.findActiveByFacts(factIds).stream().map(this::fromFact).toList();
    }

    @Override
    public List<CommissionItemDTO> findByApplication(Long applicationId) {
        return itemMapper.selectList(new LambdaQueryWrapper<CommissionItem>()
                .eq(CommissionItem::getApplicationId, applicationId)
                .orderByAsc(CommissionItem::getId))
            .stream().map(this::toDTO).toList();
    }

    /** 结佣明细 → DTO */
    private CommissionItemDTO toDTO(CommissionItem item) {
        CommissionItemDTO dto = new CommissionItemDTO();
        dto.setItemId(item.getId());
        dto.setPerformanceFactId(item.getPerformanceFactId());
        dto.setPeriod(item.getPeriod());
        dto.setApprovedMonth(item.getApprovedMonth());
        dto.setEmployeeId(item.getEmployeeId());
        dto.setDeptId(item.getDeptId());
        dto.setContractNo(item.getContractNo());
        dto.setBizType(item.getBizType());
        dto.setRoleType(item.getRoleType());
        dto.setFeeItem(item.getFeeItem());
        dto.setAmount(item.getAmount());
        dto.setStatus(item.getStatus() != null ? item.getStatus().getCode() : null);
        dto.setSource(SOURCE_ITEM);
        return dto;
    }

    /** 批量 enrich 结佣明细 with 业绩事实的合同/房源/比例信息 */
    private List<CommissionItemDTO> enrichWithFacts(List<CommissionItem> items) {
        if (items.isEmpty()) return Collections.emptyList();
        List<Long> factIds = items.stream()
            .map(CommissionItem::getPerformanceFactId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Map<Long, PerformanceFactSummaryDTO> factMap = Collections.emptyMap();
        if (!factIds.isEmpty()) {
            factMap = performanceQueryPort.findActiveByFacts(factIds).stream()
                .collect(Collectors.toMap(PerformanceFactSummaryDTO::getFactId, Function.identity(), (a, b) -> a));
        }
        List<CommissionItemDTO> result = new ArrayList<>(items.size());
        for (CommissionItem item : items) {
            CommissionItemDTO dto = toDTO(item);
            PerformanceFactSummaryDTO fact = factMap.get(item.getPerformanceFactId());
            if (fact != null) {
                dto.setBusinessDate(fact.getBusinessDate());
                dto.setSignDate(fact.getSignDate());
                dto.setOrderNo(fact.getOrderNo());
                dto.setPropertyAddress(fact.getPropertyAddress());
                dto.setShareRatio(fact.getShareRatio());
                if (dto.getContractNo() == null) dto.setContractNo(fact.getContractNo());
            }
            result.add(dto);
        }
        return result;
    }

    /** 业绩事实 → DTO（★ 原样透传，不折算） */
    private CommissionItemDTO fromFact(PerformanceFactSummaryDTO fact) {
        CommissionItemDTO dto = new CommissionItemDTO();
        dto.setItemId(null);
        dto.setPerformanceFactId(fact.getFactId());
        dto.setPeriod(fact.getPeriod());
        dto.setEmployeeId(fact.getEmployeeId());
        dto.setEmployeeCode(fact.getEmployeeCode());
        dto.setDeptId(fact.getDeptId());
        dto.setContractNo(fact.getContractNo());
        dto.setOrderNo(fact.getOrderNo());
        dto.setBusinessDate(fact.getBusinessDate());
        dto.setSignDate(fact.getSignDate());
        dto.setPropertyAddress(fact.getPropertyAddress());
        dto.setShareRatio(fact.getShareRatio());
        dto.setBizType(fact.getBizType());
        dto.setRoleType(fact.getRoleType());
        dto.setAmount(fact.getAmount());
        dto.setSource(SOURCE_FACT);
        return dto;
    }
}
