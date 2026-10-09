package com.panjia.received.adapter;

import com.panjia.contracts.dto.HistoryRealFactDTO;
import com.panjia.contracts.dto.PerformanceContractSummaryDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.dto.ReceivedAlignmentResultDTO;
import com.panjia.contracts.event.EventPort;
import com.panjia.contracts.event.PerformanceFactReversedEvent;
import com.panjia.contracts.port.ReceivedRealFactPort;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.util.MoneyUtil;
import com.panjia.received.domain.ReceivedContract;
import com.panjia.received.domain.ReceivedDetail;
import com.panjia.received.mapper.ReceivedContractMapper;
import com.panjia.received.mapper.ReceivedDetailMapper;
import com.panjia.received.mapper.ReceivedRealFactMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.system.api.ConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 实收「事实」跨域端口实现（PERF_REAL 拆表后的唯一读写落地处）。
 * <p>
 * 底表 {@code pj_received_detail(rd) + pj_received_contract(rc)}，
 * 经 {@link ReceivedRealFactMapper} 的 JOIN SQL 输出 contracts 层 DTO；
 * 写操作复刻 pj_perf_fact 时代的 supersede 状态机：
 * 旧 ACTIVE 行置 REVERSED（reversal_type=SUPERSEDE/MANUAL_ADJUST），
 * 再插同 source_key 的新 ACTIVE 行（部分唯一索引 uk_received_detail_anchor 天然支持），
 * 并发 {@link PerformanceFactReversedEvent} 让结佣域按分治表联动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceivedRealFactAdapter implements ReceivedRealFactPort {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REVERSED = "REVERSED";
    /** ReversedReason.SUPERSEDE（替换冲销） */
    private static final String REASON_SUPERSEDE = "SUPERSEDE";
    /** ReversedReason.MANUAL_ADJUST（调整单冲销） */
    private static final String REASON_MANUAL_ADJUST = "MANUAL_ADJUST";
    private static final String FACT_TYPE_EXPECT = "PERF_EXPECT";

    /** 配置项：实收应收差异容忍阈值（元），默认 1。 */
    private static final String CONFIG_DIFF_TOLERANCE = "panjia.commission.diff_tolerance";
    private static final BigDecimal DEFAULT_DIFF_TOLERANCE = BigDecimal.ONE;

    private final ReceivedRealFactMapper realMapper;
    private final ReceivedDetailMapper detailMapper;
    private final ReceivedContractMapper contractMapper;
    /** PERF_EXPECT 仍在 pj_perf_fact：对齐/对照读应收（received 临时依赖 performance） */
    private final PerformanceFactMapper factMapper;
    private final ConfigService configService;
    private final EventPort eventPort;

    // ==================== 读 ====================

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByDept(String period, Long deptId) {
        if (period == null || period.isBlank()) {
            return Collections.emptyList();
        }
        return realMapper.selectActiveByDept(period, deptId);
    }

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByEmployee(String period, Long employeeId) {
        if (period == null || period.isBlank() || employeeId == null) {
            return Collections.emptyList();
        }
        return realMapper.selectActiveByEmployee(period, employeeId);
    }

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByContract(String period, String contractNo) {
        // period 可空：空时查该合同全部期间（结佣发起月与实收月解耦）
        if (contractNo == null || contractNo.isBlank()) {
            return Collections.emptyList();
        }
        return realMapper.selectActiveByContract(period, contractNo);
    }

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByBizKeys(Collection<String> bizKeys) {
        if (bizKeys == null || bizKeys.isEmpty()) {
            return Collections.emptyList();
        }
        return realMapper.selectActiveByBizKeys(bizKeys, bizKeys);
    }

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return realMapper.selectActiveByIds(ids);
    }

    @Override
    public PerformanceFactSummaryDTO getById(Long id) {
        if (id == null) {
            return null;
        }
        return realMapper.selectByIdAnyStatus(id);
    }

    @Override
    public List<PerformanceContractSummaryDTO> listContractSummaries(String period, Long deptId, Long employeeId) {
        if (period == null || period.isBlank()) {
            return Collections.emptyList();
        }
        return realMapper.selectContractSummaries(period, deptId, employeeId);
    }

    @Override
    public long countDistinctEmployeesByKeys(String period, Collection<String> bizKeys) {
        if (period == null || period.isBlank() || bizKeys == null || bizKeys.isEmpty()) {
            return 0L;
        }
        return realMapper.selectDistinctEmployeeCountByKeys(period, bizKeys);
    }

    @Override
    public Map<String, BigDecimal> sumOriginalAmountsByKeys(String period, Collection<String> bizKeys) {
        if (period == null || period.isBlank() || bizKeys == null || bizKeys.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> rows = realMapper.selectOriginalAmountsByKeys(period, bizKeys);
        Map<String, BigDecimal> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get("bizKey");
            Object amount = row.get("originalAmount");
            if (key != null && amount instanceof BigDecimal bd) {
                result.put(String.valueOf(key), bd);
            }
        }
        return result;
    }

    @Override
    public List<HistoryRealFactDTO> listRealFactsByBatch(String period, Long batchId) {
        if (period == null || period.isBlank() || batchId == null) {
            return Collections.emptyList();
        }
        return realMapper.selectHistoryByBatch(period, batchId);
    }

    // ==================== 写：结佣调整 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<Long, Long> adjustContractDetailsAmount(String period, String contractNo,
                                                        BigDecimal targetAmount, Long operatorId, Long adjustId) {
        List<ReceivedDetail> details = realMapper.selectActiveDetailsByContract(period, contractNo);
        Map<Long, Long> mapping = new LinkedHashMap<>();
        if (details.isEmpty()) {
            return mapping;
        }
        BigDecimal total = sumDetails(details);
        BigDecimal deltaTotal = MoneyUtil.round2(nvl(targetAmount).subtract(total));
        if (MoneyUtil.isZero(deltaTotal)) {
            return mapping;
        }
        List<BigDecimal> amounts = details.stream().map(ReceivedDetail::getPerformanceAmount).toList();
        BigDecimal[] parts = MoneyUtil.allocateByAmount(amounts, deltaTotal);
        for (int i = 0; i < details.size(); i++) {
            if (MoneyUtil.isZero(parts[i])) {
                continue;
            }
            ReceivedDetail oldDetail = details.get(i);
            ReceivedDetail patch = new ReceivedDetail();
            patch.setPerformanceAmount(MoneyUtil.round2(nvl(oldDetail.getPerformanceAmount()).add(parts[i])));
            ReceivedDetail created = supersede(oldDetail, patch, operatorId, adjustId);
            mapping.put(oldDetail.getId(), created.getId());
        }
        return mapping;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long adjustDetailAmount(Long detailId, BigDecimal targetAmount, Long operatorId, Long adjustId) {
        ReceivedDetail oldDetail = detailMapper.selectById(detailId);
        if (oldDetail == null) {
            return null;
        }
        ReceivedDetail patch = new ReceivedDetail();
        patch.setPerformanceAmount(MoneyUtil.round2(targetAmount));
        return supersede(oldDetail, patch, operatorId, adjustId).getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void voidDetail(Long detailId, Long operatorId, Long adjustId) {
        ReceivedDetail detail = detailMapper.selectById(detailId);
        if (detail == null) {
            throw new IllegalStateException("实收明细不存在：detailId=" + detailId);
        }
        if (!STATUS_ACTIVE.equals(detail.getDetailStatus())) {
            throw new IllegalStateException(
                "实收明细状态不可冲销：detailId=" + detailId + ", currentStatus=" + detail.getDetailStatus());
        }
        // 单行置 REVERSED + 关联调整单（对应 ReverseService.reverseByAdjust 语义，不插新行）
        detail.setDetailStatus(STATUS_REVERSED);
        detail.setReversalType(REASON_MANUAL_ADJUST);
        detail.setAdjustId(adjustId);
        detail.setOperatorId(operatorId);
        detailMapper.updateById(detail);
        refreshContractAggregate(detail.getContractId());
        emitReversed(detail.getPeriod(), List.of(detailId), REASON_MANUAL_ADJUST);
        log.info("[实收冲销-调整单] 明细冲销完成：detailId={}, adjustId={}", detailId, adjustId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long transferDetail(Long detailId, Long targetDeptId, Long operatorId, Long adjustId) {
        ReceivedDetail oldDetail = detailMapper.selectById(detailId);
        if (oldDetail == null) {
            return null;
        }
        ReceivedDetail patch = new ReceivedDetail();
        patch.setDeptId(targetDeptId);
        return supersede(oldDetail, patch, operatorId, adjustId).getId();
    }

    // ==================== 写：对齐应收 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReceivedAlignmentResultDTO alignReceivedToExpected(String period, String contractNo, Long operatorId) {
        List<ReceivedDetail> realDetails = realMapper.selectActiveDetailsByContract(period, contractNo);
        List<PerformanceFact> expectFacts = factMapper.selectActiveFactsByContractNo(
            period, FACT_TYPE_EXPECT, contractNo);

        BigDecimal before = sumDetails(realDetails);
        BigDecimal expectedTotal = expectFacts.stream()
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        ReceivedAlignmentResultDTO result = new ReceivedAlignmentResultDTO();
        result.setPeriod(period);
        result.setContractNo(contractNo);
        result.setReceivedTotalBefore(before);
        result.setExpectedTotal(expectedTotal);

        // 拆表后 rd.source_key（order|工号|期间|日期）与 PERF_EXPECT source_key 不同源，
        // 按业务身份（员工工号 + 角色）分组配对；当前数据每组恰一条 rd（2026-08 实测 292/292）。
        Map<String, List<ReceivedDetail>> realGroups = groupDetails(realDetails);
        Map<String, List<PerformanceFact>> expectGroups = groupFacts(expectFacts);

        List<Long> createdIds = new ArrayList<>();
        Map<Long, Long> oldToNew = new LinkedHashMap<>();
        int aligned = 0;
        for (Map.Entry<String, List<ReceivedDetail>> entry : realGroups.entrySet()) {
            List<ReceivedDetail> rdRows = entry.getValue();
            List<PerformanceFact> exRows = expectGroups.getOrDefault(entry.getKey(), List.of());
            if (exRows.isEmpty()) {
                // 无对应应收（实收多出行）：保持不变
                log.warn("[实收对齐] 实收明细无对应应收事实，保持不变：detailIds={}, group={}",
                    rdRows.stream().map(ReceivedDetail::getId).toList(), entry.getKey());
                continue;
            }
            BigDecimal realSum = sumDetails(rdRows);
            BigDecimal expSum = exRows.stream()
                .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (withinTolerance(realSum, expSum)
                && shareRatiosEqual(rdRows, exRows)) {
                continue;
            }
            // 目标金额/系数：单行组吸收应收组全部金额；等多行组按顺序一一配对
            int pairCount = rdRows.size() == 1 ? 1 : Math.min(rdRows.size(), exRows.size());
            for (int i = 0; i < pairCount; i++) {
                ReceivedDetail rdRow = rdRows.get(i);
                BigDecimal targetAmount = rdRows.size() == 1 ? expSum
                    : nvl(exRows.get(i).getPerformanceAmount());
                BigDecimal targetShare = rdRows.size() == 1 ? exRows.get(0).getShareRatio()
                    : exRows.get(i).getShareRatio();
                if (rdRows.size() != 1 && rdRows.size() != exRows.size()) {
                    log.warn("[实收对齐] 实收/应收明细行数不一致，按顺序配对，余量保持不变：group={}, real={}, expect={}",
                        entry.getKey(), rdRows.size(), exRows.size());
                }
                if (withinTolerance(nvl(rdRow.getPerformanceAmount()), targetAmount)
                    && Objects.equals(rdRow.getShareRatio(), targetShare)) {
                    continue;
                }
                ReceivedDetail patch = new ReceivedDetail();
                patch.setPerformanceAmount(targetAmount);
                patch.setShareRatio(targetShare);
                ReceivedDetail created = supersede(rdRow, patch, operatorId, null);
                createdIds.add(created.getId());
                oldToNew.put(rdRow.getId(), created.getId());
                aligned++;
            }
        }

        Map<Long, PerformanceFactSummaryDTO> createdSummaries = new HashMap<>();
        if (!createdIds.isEmpty()) {
            for (PerformanceFactSummaryDTO dto : realMapper.selectActiveByIds(createdIds)) {
                createdSummaries.put(dto.getFactId(), dto);
            }
        }
        for (Map.Entry<Long, Long> e : oldToNew.entrySet()) {
            ReceivedAlignmentResultDTO.Mapping mapping = new ReceivedAlignmentResultDTO.Mapping();
            mapping.setOldFactId(e.getKey());
            mapping.setNewFact(createdSummaries.get(e.getValue()));
            if (mapping.getNewFact() != null) {
                result.getMappings().add(mapping);
            }
        }

        BigDecimal after = sumDetails(realMapper.selectActiveDetailsByContract(period, contractNo));
        result.setReceivedTotalAfter(after);
        log.info("[实收对齐] 合同实收已对齐应收：period={}, contractNo={}, 对齐明细数={}, before={}, after={}, expect={}",
            period, contractNo, aligned, before, after, expectedTotal);
        return result;
    }

    // ==================== 内部方法 ====================

    /**
     * 实收明细 supersede 状态机（对应 ReverseService.supersede）：
     * 旧 ACTIVE → REVERSED(reversal_type=SUPERSEDE)、expire_date=新生效日前一天，
     * 再插同 source_key 的新 ACTIVE 行（先改旧行再插新行，满足部分唯一索引），
     * 重算合同聚合并发冲销事件。
     */
    private ReceivedDetail supersede(ReceivedDetail oldDetail, ReceivedDetail patch,
                                     Long operatorId, Long adjustId) {
        if (!STATUS_ACTIVE.equals(oldDetail.getDetailStatus())) {
            throw new IllegalStateException(
                "实收明细状态不可冲销：detailId=" + oldDetail.getId()
                    + ", currentStatus=" + oldDetail.getDetailStatus());
        }
        ReceivedDetail newDetail = copyForSupersede(oldDetail);
        if (patch.getPerformanceAmount() != null) {
            newDetail.setPerformanceAmount(patch.getPerformanceAmount());
        }
        if (patch.getShareRatio() != null) {
            newDetail.setShareRatio(patch.getShareRatio());
        }
        if (patch.getDeptId() != null) {
            newDetail.setDeptId(patch.getDeptId());
        }
        newDetail.setAdjustId(adjustId);
        newDetail.setOperatorId(operatorId);

        // 1. 旧行转 REVERSED（必须先于新行 INSERT）
        oldDetail.setDetailStatus(STATUS_REVERSED);
        oldDetail.setReversalType(REASON_SUPERSEDE);
        oldDetail.setOperatorId(operatorId);
        if (newDetail.getEffectiveDate() != null) {
            oldDetail.setExpireDate(newDetail.getEffectiveDate().minusDays(1));
        }
        detailMapper.updateById(oldDetail);

        // 2. 插新 ACTIVE 行（同 source_key；source_batch_id 保留但 adjust_id 非空，批次冲销会跳过）
        detailMapper.insert(newDetail);

        // 3. 重算合同级聚合 + 发冲销事件（结佣域分治表联动）
        refreshContractAggregate(newDetail.getContractId());
        emitReversed(oldDetail.getPeriod(), List.of(oldDetail.getId()), REASON_SUPERSEDE);
        log.info("[实收冲销-替换] 明细替换完成：oldDetailId={}, newDetailId={}",
            oldDetail.getId(), newDetail.getId());
        return newDetail;
    }

    /** 复制全部业务字段（含 receivedApplyId 关联）；状态/冲销字段按新 ACTIVE 行重置。 */
    private ReceivedDetail copyForSupersede(ReceivedDetail oldDetail) {
        ReceivedDetail n = new ReceivedDetail();
        n.setContractId(oldDetail.getContractId());
        n.setEmployeeId(oldDetail.getEmployeeId());
        n.setDeptId(oldDetail.getDeptId());
        n.setEmployeeExternalCode(oldDetail.getEmployeeExternalCode());
        n.setRoleType(oldDetail.getRoleType());
        n.setRoleName(oldDetail.getRoleName());
        n.setShareRatio(oldDetail.getShareRatio());
        n.setPerformanceAmount(oldDetail.getPerformanceAmount());
        n.setFeeItem(oldDetail.getFeeItem());
        n.setPeriod(oldDetail.getPeriod());
        n.setEffectiveDate(oldDetail.getEffectiveDate());
        n.setExpireDate(null);
        n.setSourceKey(oldDetail.getSourceKey());
        n.setSourceBatchId(oldDetail.getSourceBatchId());
        n.setNormalizedRecordId(oldDetail.getNormalizedRecordId());
        n.setReceivedApplyId(oldDetail.getReceivedApplyId());
        n.setDetailStatus(STATUS_ACTIVE);
        n.setAdjustId(null);
        n.setReversalType(null);
        n.setRefundOfDetailId(oldDetail.getRefundOfDetailId());
        return n;
    }

    /** 重算实收合同级聚合（period_total_received / item_count），与明细 ACTIVE 集合保持一致。 */
    private void refreshContractAggregate(Long contractId) {
        if (contractId == null) {
            return;
        }
        List<ReceivedDetail> active = detailMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReceivedDetail>()
                .eq(ReceivedDetail::getContractId, contractId)
                .eq(ReceivedDetail::getDetailStatus, STATUS_ACTIVE));
        ReceivedContract contract = contractMapper.selectById(contractId);
        if (contract == null) {
            return;
        }
        contract.setItemCount(active.size());
        contract.setPeriodTotalReceived(active.stream()
            .map(d -> d.getPerformanceAmount() == null ? BigDecimal.ZERO : d.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add));
        contractMapper.updateById(contract);
    }

    private void emitReversed(String period, List<Long> detailIds, String reason) {
        if (detailIds == null || detailIds.isEmpty()) {
            return;
        }
        PerformanceFactReversedEvent event = new PerformanceFactReversedEvent();
        event.setPeriod(period);
        List<String> ids = new ArrayList<>(detailIds.size());
        for (Long id : detailIds) {
            ids.add(String.valueOf(id));
        }
        event.setFactIds(ids);
        event.setReason(reason);
        eventPort.emit(event);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal sumDetails(List<ReceivedDetail> details) {
        return details.stream()
            .map(d -> d.getPerformanceAmount() == null ? BigDecimal.ZERO : d.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String groupKey(String employeeCode, String roleType) {
        return (employeeCode == null ? "" : employeeCode) + '\u0001' + (roleType == null ? "" : roleType);
    }

    private static Map<String, List<ReceivedDetail>> groupDetails(List<ReceivedDetail> details) {
        Map<String, List<ReceivedDetail>> map = new LinkedHashMap<>();
        for (ReceivedDetail d : details) {
            map.computeIfAbsent(groupKey(d.getEmployeeExternalCode(), d.getRoleType()),
                k -> new ArrayList<>()).add(d);
        }
        return map;
    }

    private static Map<String, List<PerformanceFact>> groupFacts(List<PerformanceFact> facts) {
        Map<String, List<PerformanceFact>> map = new LinkedHashMap<>();
        for (PerformanceFact f : facts) {
            map.computeIfAbsent(groupKey(f.getEmployeeExternalCode(), f.getRoleType()),
                k -> new ArrayList<>()).add(f);
        }
        return map;
    }

    /** 组内实收系数是否与应收一致（单行组取应收首行系数；等多行组逐行比）。 */
    private boolean shareRatiosEqual(List<ReceivedDetail> rdRows, List<PerformanceFact> exRows) {
        if (rdRows.size() == 1) {
            return Objects.equals(rdRows.get(0).getShareRatio(), exRows.get(0).getShareRatio());
        }
        if (rdRows.size() != exRows.size()) {
            return false;
        }
        for (int i = 0; i < rdRows.size(); i++) {
            if (!Objects.equals(rdRows.get(i).getShareRatio(), exRows.get(i).getShareRatio())) {
                return false;
            }
        }
        return true;
    }

    /** 金额容忍判定：|a - b| <= 容忍阈值（默认 1 元，系统参数 panjia.commission.diff_tolerance）。 */
    private boolean withinTolerance(BigDecimal a, BigDecimal b) {
        BigDecimal tolerance = configService.getConfigDecimal(CONFIG_DIFF_TOLERANCE);
        if (tolerance == null) {
            tolerance = DEFAULT_DIFF_TOLERANCE;
        }
        return nvl(a).subtract(nvl(b)).abs().compareTo(tolerance) <= 0;
    }
}
