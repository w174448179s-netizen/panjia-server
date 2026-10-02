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
    private final com.panjia.contracts.port.EmployeeMainDataQueryPort employeeMainDataQueryPort;

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
        // 未发起明细与发起后口径一致（2026-09-27 定稿新签口径）：明细源 = 该部门当月实收合同
        // 对应的跨月 ACTIVE 新签事实逐行，金额 = 事实当前值（调整后）；实收事实（含空经纪人
        // 行，仅展示）只用于收集业务键，不产生明细；金额为 0 的明细不展示
        List<PerformanceFactSummaryDTO> realFacts = performanceQueryPort
            .findActiveByDept(period, deptId, FACT_TYPE_REAL);
        if (realFacts.isEmpty()) return Collections.emptyList();
        java.util.Set<String> bizKeys = new java.util.LinkedHashSet<>();
        for (PerformanceFactSummaryDTO f : realFacts) {
            // 统一以订单号为业务锚点（合同号可能为空，不再作为独立键收集）；
            // findActiveByBizKeys 内部仍会对 contract_no 列做 OR 匹配兜底（历史数据兼容）
            if (f.getOrderNo() != null && !f.getOrderNo().isBlank()) {
                bizKeys.add(f.getOrderNo());
            }
        }
        List<PerformanceFactSummaryDTO> expects =
            performanceQueryPort.findActiveByBizKeys(bizKeys, FACT_TYPE_EXPECT);
        List<CommissionItemDTO> result = new ArrayList<>(expects.size());
        for (PerformanceFactSummaryDTO e : expects) {
            // 新签事实必有人（导入未匹配即拦截）；空归属行为防御性过滤
            if (e.getEmployeeId() == null) {
                continue;
            }
            if (e.getAmount() == null || e.getAmount().signum() == 0) {
                continue;
            }
            result.add(fromFact(e));
        }
        return result;
    }

    @Override
    public List<CommissionItemDTO> findByApplication(Long applicationId) {
        return itemMapper.selectList(new LambdaQueryWrapper<CommissionItem>()
                .eq(CommissionItem::getApplicationId, applicationId)
                .orderByAsc(CommissionItem::getId))
            .stream().map(this::toDTO).toList();
    }

    /** 结佣明细 → DTO（展示字段全部取本表冻结快照，不再跨域关联事实表） */
    private CommissionItemDTO toDTO(CommissionItem item) {
        CommissionItemDTO dto = new CommissionItemDTO();
        dto.setItemId(item.getId());
        dto.setPerformanceFactId(item.getPerformanceFactId());
        dto.setPeriod(item.getPeriod());
        dto.setApprovedMonth(item.getApprovedMonth());
        dto.setEmployeeId(item.getEmployeeId());
        dto.setDeptId(item.getDeptId());
        dto.setContractNo(item.getContractNo());
        dto.setOrderNo(item.getOrderNo());
        dto.setBusinessDate(item.getBusinessDate());
        dto.setPropertyAddress(item.getPropertyAddress());
        dto.setShareRatio(item.getShareRatio());
        dto.setEmployeeCode(item.getEmployeeCode());
        dto.setBizType(item.getBizType());
        dto.setRoleType(item.getRoleType());
        dto.setFeeItem(item.getFeeItem());
        dto.setAmount(item.getAmount());
        dto.setStatus(item.getStatus() != null ? item.getStatus().getCode() : null);
        dto.setSource(SOURCE_ITEM);
        return dto;
    }

    /**
     * 结佣明细批量补员工主数据（姓名/门店名实时翻译；工号已在明细快照中，仅在快照缺失时兜底）。
     * 签约日期/订单号/房源地址/角色占比等展示字段均为本表冻结快照，无需再查事实表。
     */
    private List<CommissionItemDTO> enrichWithFacts(List<CommissionItem> items) {
        if (items.isEmpty()) return Collections.emptyList();
        java.util.Set<Long> empIds = items.stream()
            .map(CommissionItem::getEmployeeId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, com.panjia.contracts.dto.EmployeeMainDataDTO> empMap = empIds.isEmpty()
            ? Collections.emptyMap()
            : employeeMainDataQueryPort.listByIds(empIds);
        List<CommissionItemDTO> result = new ArrayList<>(items.size());
        for (CommissionItem item : items) {
            CommissionItemDTO dto = toDTO(item);
            com.panjia.contracts.dto.EmployeeMainDataDTO emp =
                item.getEmployeeId() == null ? null : empMap.get(item.getEmployeeId());
            if (emp != null) {
                if (dto.getEmployeeCode() == null) dto.setEmployeeCode(emp.getEmployeeCode());
                dto.setEmployeeName(emp.getEmployeeName());
                dto.setDeptName(emp.getDeptName());
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
        dto.setEmployeeName(fact.getEmployeeName());
        dto.setDeptId(fact.getDeptId());
        dto.setDeptName(fact.getDeptName());
        dto.setContractNo(fact.getContractNo());
        dto.setOrderNo(fact.getOrderNo());
        dto.setBusinessDate(fact.getBusinessDate());
        dto.setPropertyAddress(fact.getPropertyAddress());
        dto.setShareRatio(fact.getShareRatio());
        dto.setBizType(fact.getBizType());
        dto.setRoleType(fact.getRoleType());
        dto.setAmount(fact.getAmount());
        dto.setSource(SOURCE_FACT);
        return dto;
    }
}
