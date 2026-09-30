package com.panjia.received.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.common.util.DeptScopeUtils;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.ReceivedApply;
import com.panjia.performance.domain.ReceivedApplyStatus;
import com.panjia.performance.domain.vo.BatchApproveResultVo;
import com.panjia.performance.dto.BatchFactBindRow;
import com.panjia.performance.domain.bo.ReceivedApplyBo;
import com.panjia.performance.domain.vo.ReceivedContractMetricsVo;
import com.panjia.performance.domain.vo.ReceivedFactDetailVo;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.mapper.ReceivedApplyMapper;
import com.panjia.received.service.IReceivedApplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.event.EventPort;
import com.panjia.contracts.event.ReceivedApprovedEvent;
import com.panjia.contracts.port.ApprovalAction;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ApprovalStartCmd;
import com.panjia.contracts.port.ConversionFactorPort;
import com.panjia.contracts.port.MyTaskBrief;
import com.panjia.contracts.port.ReceivedApplyPort;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.ConfigService;
import org.dromara.system.api.DeptService;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 实收业绩审批单服务实现（§2）。
 * <p>
 * 自动建单：贝壳业绩导入归档后按合同聚合 PERF_REAL 事实自动建单提交（§2.1）；
 * 发起人路由（§2.2）：店长→财务→总监；财务发起→总监；总监发起→直接通过；
 * panjia.flow.skip_finance=true 时财务节点系统自动跳过。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceivedApplyServiceImpl implements IReceivedApplyService, ReceivedApplyPort {

    private static final String NODE_FINANCE = "rcv_finance";
    private static final String NODE_DIRECTOR = "rcv_director";
    private static final String FACT_TYPE_EXPECT = FactType.PERF_EXPECT.getCode();

    private static final String CONFIG_SKIP_FINANCE = "panjia.flow.skip_finance";
    private static final DateTimeFormatter APPLY_NO_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final ReceivedApplyMapper applyMapper;
    private final PerformanceFactMapper factMapper;
    private final com.panjia.received.mapper.ReceivedContractMapper contractMapper;
    private final com.panjia.received.mapper.ReceivedDetailMapper detailMapper;
    private final ApprovalPort approvalPort;
    private final ConfigService configService;
    private final TaskExecutor taskExecutor;
    /** 折算因子公共方法（取比例 / 金额乘算的唯一入口） */
    private final ConversionFactorPort conversionFactorPort;
    /** 部门子树解析（店长/总监数据权限范围） */
    private final DeptService deptService;
    /** 实收审批通过事件（结佣域按合同自动产生结佣记录，outbox 原子提交） */
    private final EventPort eventPort;

    // ==================== 导入自动建单（§2.1） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int autoCreateForBatch(Long batchId, String period, Long operatorId) {
        if (batchId == null || StringUtils.isBlank(period)) {
            return 0;
        }
        // 兜底：事件驱动场景可能无登录上下文
        if (operatorId == null) {
            try {
                operatorId = LoginHelper.getUserId();
            } catch (Exception ignored) {
            }
        }
        if (operatorId == null) {
            operatorId = 1L;
        }
        // 拆表后 PERF_REAL 已迁出到实收域 → 从 pj_received_detail + pj_received_contract 查
        List<PerformanceFact> realFacts = loadRealFactsFromReceivedTables(batchId, period);
        if (realFacts.isEmpty()) {
            log.info("[实收审批] 批次无待建单实收明细：batchId={}, period={}", batchId, period);
            return 0;
        }
        return createApplyForRealFactsInternal(realFacts, period, operatorId, batchId);
    }

    /**
     * 从实收域两张表（pj_received_detail + pj_received_contract）查询批次内实收数据，
     * 映射成 PerformanceFact（createApplyForRealFactsInternal 共用）。
     * <p>拆表后 PERF_REAL 已迁出 pj_perf_fact，此方法是新的数据入口。
     */
    private List<PerformanceFact> loadRealFactsFromReceivedTables(Long batchId, String period) {
        // 1. 查批次内所有实收合同（含 detail_status=ACTIVE 的明细）
        List<com.panjia.received.domain.ReceivedDetail> details = detailMapper.selectList(
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
                .eq(com.panjia.received.domain.ReceivedDetail::getSourceBatchId, batchId)
                .eq(com.panjia.received.domain.ReceivedDetail::getPeriod, period)
                .eq(com.panjia.received.domain.ReceivedDetail::getDetailStatus, "ACTIVE"));
        if (details.isEmpty()) return List.of();

        // 2. 批量查合同
        List<Long> contractIds = details.stream().map(com.panjia.received.domain.ReceivedDetail::getContractId).distinct().toList();
        Map<Long, com.panjia.received.domain.ReceivedContract> contractMap = contractMapper.selectBatchIds(contractIds)
            .stream().collect(Collectors.toMap(com.panjia.received.domain.ReceivedContract::getId, c -> c));

        // 3. 明细行 → PerformanceFact
        return mapDetailsToFacts(details, contractMap, batchId);
    }

    /**
     * 实收明细行（JOIN 合同）→ 内存 PerformanceFact（仅填建单/手工提交链路用到的字段）。
     * <p>批次自动建单、按 factId(rd.id) 建单、手工提交镜像三条路径共用，保证映射口径唯一。
     */
    private List<PerformanceFact> mapDetailsToFacts(List<com.panjia.received.domain.ReceivedDetail> details,
                                                     Map<Long, com.panjia.received.domain.ReceivedContract> contractMap,
                                                     Long batchId) {
        List<PerformanceFact> result = new ArrayList<>(details.size());
        for (com.panjia.received.domain.ReceivedDetail d : details) {
            com.panjia.received.domain.ReceivedContract c = contractMap.get(d.getContractId());
            if (c == null) continue;
            PerformanceFact f = new PerformanceFact();
            f.setId(d.getId());
            f.setBatchId(batchId != null ? batchId : d.getSourceBatchId());
            f.setPeriod(d.getPeriod());
            f.setOrderNo(c.getOrderNo());
            f.setContractNo(c.getContractNo());
            f.setBizType(c.getBizType());
            f.setPropertyAddress(c.getPropertyAddress());
            f.setDeptId(d.getDeptId() != null ? d.getDeptId() : c.getDeptId());
            f.setEmployeeId(d.getEmployeeId());
            f.setEmployeeExternalCode(d.getEmployeeExternalCode());
            f.setRoleType(d.getRoleType());
            f.setRoleName(d.getRoleName());
            f.setShareRatio(d.getShareRatio());
            f.setPerformanceAmount(d.getPerformanceAmount());
            f.setFeeItem(d.getFeeItem());
            f.setEffectiveDate(d.getEffectiveDate());
            f.setFactType(FactType.PERF_REAL);
            f.setFactStatus(com.panjia.performance.domain.FactStatus.ACTIVE);
            f.setBusinessDate(c.getBusinessDate());
            f.setSourceKey(d.getSourceKey());
            result.add(f);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int createApplyForRealFacts(List<PerformanceFact> realFacts, String period,
                                       Long operatorId, Long batchId) {
        if (realFacts == null || realFacts.isEmpty() || StringUtils.isBlank(period)) {
            return 0;
        }
        if (operatorId == null) {
            try {
                operatorId = LoginHelper.getUserId();
            } catch (Exception ignored) {
            }
        }
        if (operatorId == null) {
            operatorId = 1L;
        }
        return createApplyForRealFactsInternal(realFacts, period, operatorId, batchId);
    }

    /**
     * 公共建单段（按订单号分组 → 活跃审批单 → 新建/合并 → 绑定 → startWorkflow）。
     * 两条路径共用：autoCreateForBatch（导入归档）和手工提交实收。
     */
    private int createApplyForRealFactsInternal(List<PerformanceFact> realFacts, String period,
                                                Long operatorId, Long batchId) {
        // ===== 1. 按订单号分组（业务键 = orderNo） =====
        Map<String, List<PerformanceFact>> factsByOrderNo = new LinkedHashMap<>();
        for (PerformanceFact f : realFacts) {
            String key = f.getOrderNo();
            if (StringUtils.isBlank(key)) {
                continue;
            }
            factsByOrderNo.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
        }
        if (factsByOrderNo.isEmpty()) {
            log.info("[实收审批] PERF_REAL 无有效订单号，跳过建单");
            return 0;
        }
        List<String> bizKeys = new ArrayList<>(factsByOrderNo.keySet());
        // ===== 2. 批量预加载（2 次 SQL） =====
        // ① 活跃审批单（DRAFT/SUBMITTED/APPROVED）
        Map<String, ReceivedApply> activeApplies = loadActiveAppliesBatch(period, bizKeys);
        // ② PERF_EXPECT 应收合计（用于快照/展示，不影响审批逻辑）
        Map<String, BigDecimal> expectedMap = factMapper.selectExpectSumsByBizKeys(period, bizKeys)
            .stream()
            .collect(Collectors.toMap(BatchFactBindRow::getBizKey,
                r -> r.getAmount() == null ? BigDecimal.ZERO : r.getAmount(),
                (a, b) -> a));

        int created = 0;
        for (Map.Entry<String, List<PerformanceFact>> entry : factsByOrderNo.entrySet()) {
            String bizKey = entry.getKey();
            List<PerformanceFact> rows = entry.getValue();
            List<Long> factIds = rows.stream().map(PerformanceFact::getId).toList();
            BigDecimal realSum = rows.stream()
                .map(r -> r.getPerformanceAmount() == null ? BigDecimal.ZERO : r.getPerformanceAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            // uniqueDeptId：同组内全部事实 deptId 一致才取，否则 null（可能跨部门）
            Long uniqueDeptId = null;
            Set<Long> deptSet = new LinkedHashSet<>();
            for (PerformanceFact f : rows) {
                if (f.getDeptId() != null) deptSet.add(f.getDeptId());
            }
            if (deptSet.size() == 1) uniqueDeptId = deptSet.iterator().next();
            BigDecimal expectedAmount = expectedMap.getOrDefault(bizKey, BigDecimal.ZERO);
            // 取组内首条事实的快照字段（bizType/contractNo/propertyAddress）
            PerformanceFact first = rows.get(0);
            try {
                ReceivedApply existing = activeApplies.get(bizKey);
                if (existing == null) {
                    // ===== 新建 =====
                    ReceivedApply apply = newApplyFromFact(period, first, batchId, operatorId,
                        rows.size(), realSum, expectedAmount, uniqueDeptId);
                    insertApply(apply);
                    bindFacts(factIds, apply.getId());
                    startWorkflow(apply, apply.getApplicantId(), Set.of());
                    created++;
                    log.info("[实收审批] 建单并提交：applyNo={}, orderNo={}, received={}, batchId={}",
                        apply.getApplyNo(), apply.getOrderNo(), apply.getReceivedAmount(), batchId);
                } else if (existing.getStatus() == ReceivedApplyStatus.APPROVED) {
                    log.info("[实收审批] 订单本月审批单已 APPROVED，跳过新事实合并：applyId={}, orderNo={}",
                        existing.getId(), bizKey);
                } else {
                    // ===== 合并 DRAFT/SUBMITTED =====
                    bindFacts(factIds, existing.getId());
                    existing.setItemCount((existing.getItemCount() == null ? 0 : existing.getItemCount())
                        + rows.size());
                    existing.setReceivedAmount(
                        (existing.getReceivedAmount() == null ? BigDecimal.ZERO : existing.getReceivedAmount())
                            .add(realSum));
                    existing.setExpectedAmount(expectedAmount);
                    if (existing.getItemCount() == 1 && existing.getDeptId() == null) {
                        existing.setDeptId(uniqueDeptId);
                    }
                    if (StringUtils.isBlank(existing.getBizType())) {
                        existing.setBizType(first.getBizType());
                    }
                    applyMapper.updateById(existing);
                    log.info("[实收审批] 新实收事实合并入既有审批单：applyId={}, orderNo={}, status={}",
                        existing.getId(), bizKey, existing.getStatus());
                }
            } catch (DuplicateKeyException e) {
                log.warn("[实收审批] 并发建单撞唯一索引，跳过：period={}, orderNo={}", period, bizKey);
            }
        }
        log.info("[实收审批] 建单完成：period={}, 新建={}, 批次={}", period, created, batchId);
        return created;
    }

    /** 从 PERF_REAL 事实构造 ReceivedApply（批量/手工共用，替代 newApplyFromGroup） */
    private ReceivedApply newApplyFromFact(String period, PerformanceFact first, Long batchId,
                                           Long applicantId, int itemCount, BigDecimal realSum,
                                           BigDecimal expectedAmount, Long uniqueDeptId) {
        ReceivedApply apply = new ReceivedApply();
        apply.setApplyNo("RCV" + LocalDateTime.now().format(APPLY_NO_FORMATTER));
        apply.setPeriod(period);
        apply.setBatchId(batchId);
        apply.setApplicantId(applicantId);
        apply.setStatus(ReceivedApplyStatus.DRAFT);
        apply.setOrderNo(first.getOrderNo());
        apply.setContractNo(first.getContractNo());
        apply.setPropertyAddress(first.getPropertyAddress());
        apply.setBizType(first.getBizType());
        apply.setBusinessDate(first.getBusinessDate());
        apply.setItemCount(itemCount);
        apply.setReceivedAmount(realSum);
        apply.setExpectedAmount(expectedAmount);
        apply.setDeptId(uniqueDeptId);
        return apply;
    }

    /**
     * 拆表后贝壳实收建单：字段取自 pj_received_contract（实收数据已不落 pj_perf_fact）。
     */
    private ReceivedApply newApplyFromContract(String period,
                                               com.panjia.received.domain.ReceivedContract contract,
                                               Long batchId, Long applicantId, int itemCount,
                                               BigDecimal realSum, BigDecimal expectedAmount, Long uniqueDeptId) {
        ReceivedApply apply = new ReceivedApply();
        apply.setApplyNo("RCV" + LocalDateTime.now().format(APPLY_NO_FORMATTER));
        apply.setPeriod(period);
        apply.setBatchId(batchId);
        apply.setApplicantId(applicantId);
        apply.setStatus(ReceivedApplyStatus.DRAFT);
        apply.setOrderNo(contract.getOrderNo());
        apply.setContractNo(contract.getContractNo());
        apply.setPropertyAddress(contract.getPropertyAddress());
        apply.setBizType(contract.getBizType());
        apply.setBusinessDate(contract.getBusinessDate());
        apply.setItemCount(itemCount);
        apply.setReceivedAmount(realSum);
        apply.setExpectedAmount(expectedAmount);
        apply.setDeptId(uniqueDeptId);
        return apply;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int autoCreateApprovedForBatch(Long batchId, String period, Long operatorId) {
        if (batchId == null || StringUtils.isBlank(period)) {
            return 0;
        }
        // 兜底：事件驱动场景可能无登录上下文
        if (operatorId == null) {
            try {
                operatorId = LoginHelper.getUserId();
            } catch (Exception ignored) {
            }
        }
        if (operatorId == null) {
            operatorId = 1L;
        }
        // 拆表后历史工资批次实收落在 rd/rc：取批次内未挂单 ACTIVE 明细（period 同批一致仍显式过滤）
        List<com.panjia.received.domain.ReceivedDetail> batchDetails = detailMapper.selectList(
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
                .eq(com.panjia.received.domain.ReceivedDetail::getSourceBatchId, batchId)
                .eq(com.panjia.received.domain.ReceivedDetail::getPeriod, period)
                .eq(com.panjia.received.domain.ReceivedDetail::getDetailStatus, "ACTIVE")
                .isNull(com.panjia.received.domain.ReceivedDetail::getReceivedApplyId));
        if (batchDetails.isEmpty()) {
            log.info("[实收审批] 历史批次无待建单实收明细：batchId={}, period={}", batchId, period);
            return 0;
        }
        // 批量加载合同并按订单号分组（空订单号行无法建单，跳过）
        List<Long> contractIds = batchDetails.stream()
            .map(com.panjia.received.domain.ReceivedDetail::getContractId).distinct().toList();
        Map<Long, com.panjia.received.domain.ReceivedContract> contractById = contractMapper.selectBatchIds(contractIds)
            .stream().collect(Collectors.toMap(com.panjia.received.domain.ReceivedContract::getId, c -> c));
        Map<String, List<com.panjia.received.domain.ReceivedDetail>> rowsByOrder = new LinkedHashMap<>();
        Map<String, com.panjia.received.domain.ReceivedContract> contractByOrder = new HashMap<>();
        for (com.panjia.received.domain.ReceivedDetail d : batchDetails) {
            com.panjia.received.domain.ReceivedContract c = contractById.get(d.getContractId());
            if (c == null || StringUtils.isBlank(c.getOrderNo())) {
                continue;
            }
            // 与老口径一致：0 元行不绑定、不计数（selectBatchUnboundRealFacts 的 amount &lt;&gt; 0 过滤）
            if (d.getPerformanceAmount() == null
                || d.getPerformanceAmount().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            rowsByOrder.computeIfAbsent(c.getOrderNo(), k -> new ArrayList<>()).add(d);
            contractByOrder.putIfAbsent(c.getOrderNo(), c);
        }
        if (rowsByOrder.isEmpty()) {
            log.info("[实收审批] 历史批次无有效订单号/非零实收，跳过直建：batchId={}", batchId);
            return 0;
        }
        // ===== 批量预加载：活跃审批单 + PERF_EXPECT 应收合计（同老路径 2 次 SQL） =====
        List<String> bizKeys = new ArrayList<>(rowsByOrder.keySet());
        Map<String, ReceivedApply> activeApplies = loadActiveAppliesBatch(period, bizKeys);
        Map<String, BigDecimal> expectedMap = factMapper.selectExpectSumsByBizKeys(period, bizKeys)
            .stream()
            .collect(Collectors.toMap(BatchFactBindRow::getBizKey,
                r -> r.getAmount() == null ? BigDecimal.ZERO : r.getAmount(),
                (a, b) -> a));

        int created = 0;
        for (Map.Entry<String, List<com.panjia.received.domain.ReceivedDetail>> entry : rowsByOrder.entrySet()) {
            String bizKey = entry.getKey();
            List<com.panjia.received.domain.ReceivedDetail> rows = entry.getValue();
            // 幂等：同订单号当月已有活跃审批单时跳过（历史重导场景由批次 supersede 冲销重建）
            if (activeApplies.containsKey(bizKey)) {
                log.info("[实收审批] 历史直建跳过，订单当月已有审批单：applyId={}, orderNo={}",
                    activeApplies.get(bizKey).getId(), bizKey);
                continue;
            }
            List<Long> factIds = rows.stream().map(com.panjia.received.domain.ReceivedDetail::getId).toList();
            BigDecimal realSum = rows.stream()
                .map(r -> r.getPerformanceAmount() == null ? BigDecimal.ZERO : r.getPerformanceAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (realSum.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            // 明细级门店唯一时取明细值（老口径 f.dept_id），否则回退合同级门店
            Long uniqueDeptId = uniqueDetailDeptId(rows, contractByOrder.get(bizKey));
            BigDecimal expectedAmount = expectedMap.getOrDefault(bizKey, BigDecimal.ZERO);
            try {
                ReceivedApply apply = newApplyFromContract(period, contractByOrder.get(bizKey), batchId, operatorId,
                    rows.size(), realSum, expectedAmount, uniqueDeptId);
                // 审批终态直接 INSERT：APPROVED + 无流程实例（同老导入器第 8 段语义）
                apply.setStatus(ReceivedApplyStatus.APPROVED);
                apply.setApproverId(operatorId);
                apply.setApproveTime(LocalDateTime.now());
                insertApply(apply);
                bindFacts(factIds, apply.getId());
                created++;
                log.info("[实收审批] 历史批次实收审批单直建：applyNo={}, orderNo={}, received={}",
                    apply.getApplyNo(), apply.getOrderNo(), apply.getReceivedAmount());
            } catch (DuplicateKeyException e) {
                log.warn("[实收审批] 并发直建撞唯一索引，跳过：period={}, orderNo={}", period, bizKey);
            }
        }
        log.info("[实收审批] 历史批次直建完成：batchId={}, period={}, 新建={}", batchId, period, created);
        return created;
    }

    /**
     * 贝壳实收导入分流建单（§2.1.1，2026-09-27 定稿合同维度）：
     * 按「订单当月到账合计（含空经纪人行）vs 该订单跨月全部新签应收合计」自动判定每单
     * 走 APPROVED 直建还是人工审批（比较与建单均为订单/合同维度，不再按经纪人拆分）。
     * <p>
     * 比较口径（新签可能早于到账月，跨月查找）：
     * <ul>
     *   <li>到账合计 ≥ 0：≥ 应收合计 → 自动通过（APPROVED 直建，无流程实例）；
     *       &lt; 应收合计 → 人工审批（DRAFT + startWorkflow）；</li>
     *   <li>到账合计 &lt; 0（贝壳业绩事后扣减，理房通负到账）：该订单存在正当数新签
     *       （负 &lt; 正恒成立）→ 自动通过；无正当数新签（数据异常）→ 人工审批。</li>
     * </ul>
     * 自动通过后发布 {@link ReceivedApprovedEvent}，结佣域按合同自动产生结佣记录。
     * 幂等：同订单号当月已有活跃审批单时——APPROVED 跳过（防绕过审批），
     * DRAFT/SUBMITTED 直接合并绑定（从严并入人工流程）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int autoCreateForReceivedBatch(Long batchId, String period, Long operatorId) {
        if (batchId == null || StringUtils.isBlank(period)) {
            return 0;
        }
        // 兜底：事件驱动场景可能无登录上下文
        if (operatorId == null) {
            try {
                operatorId = LoginHelper.getUserId();
            } catch (Exception ignored) {
            }
        }
        if (operatorId == null) {
            operatorId = 1L;
        }
        // ===== 1. 批次内未绑定审批单的实收明细（拆表后实收数据在 pj_received_detail，不再落 pj_perf_fact） =====
        List<com.panjia.received.domain.ReceivedDetail> realDetails = detailMapper.selectList(
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
                .eq(com.panjia.received.domain.ReceivedDetail::getSourceBatchId, batchId)
                .eq(com.panjia.received.domain.ReceivedDetail::getDetailStatus, "ACTIVE")
                .isNull(com.panjia.received.domain.ReceivedDetail::getReceivedApplyId));
        if (realDetails.isEmpty()) {
            log.info("[实收审批] 贝壳实收批次无待建单实收事实：batchId={}, period={}", batchId, period);
            return 0;
        }
        // ===== 2. 经合同表按订单号分组（建单与比较维度均为订单/合同；空经纪人行一并计入到账合计） =====
        List<Long> contractIds = realDetails.stream()
            .map(com.panjia.received.domain.ReceivedDetail::getContractId).distinct().toList();
        Map<Long, com.panjia.received.domain.ReceivedContract> contractById = contractMapper
            .selectBatchIds(contractIds).stream()
            .collect(Collectors.toMap(com.panjia.received.domain.ReceivedContract::getId, c -> c));
        Map<String, List<com.panjia.received.domain.ReceivedDetail>> factsByOrder = new LinkedHashMap<>();
        Map<String, com.panjia.received.domain.ReceivedContract> contractByOrder = new HashMap<>();
        for (com.panjia.received.domain.ReceivedDetail d : realDetails) {
            com.panjia.received.domain.ReceivedContract c = contractById.get(d.getContractId());
            if (c == null || StringUtils.isBlank(c.getOrderNo())) {
                continue;
            }
            factsByOrder.computeIfAbsent(c.getOrderNo(), k -> new ArrayList<>()).add(d);
            contractByOrder.putIfAbsent(c.getOrderNo(), c);
        }
        if (factsByOrder.isEmpty()) {
            log.info("[实收审批] 贝壳实收批次无有效订单号，跳过建单：batchId={}", batchId);
            return 0;
        }
        List<String> bizKeys = new ArrayList<>(factsByOrder.keySet());
        // ===== 3. 批量查该订单全部（跨月）新签应收（PERF_EXPECT ACTIVE）→ 按订单号汇总 =====
        // 跨月口径：新签可能早于到账月（如 7 月新签、8 月到账），判定基数 = 该订单全部月份的新签合计
        //（含业绩调整后 supersede 的新事实），审批单 expectedAmount 同口径
        Map<String, BigDecimal> expectTotalByOrder = new HashMap<>();
        for (PerformanceFact e : factMapper.selectList(new LambdaQueryWrapper<PerformanceFact>()
                .eq(PerformanceFact::getFactType, FACT_TYPE_EXPECT)
                .eq(PerformanceFact::getFactStatus, com.panjia.performance.domain.FactStatus.ACTIVE)
                .in(PerformanceFact::getOrderNo, bizKeys))) {
            if (StringUtils.isBlank(e.getOrderNo())) {
                continue;
            }
            expectTotalByOrder.merge(e.getOrderNo(), nvlAmount(e.getPerformanceAmount()), BigDecimal::add);
        }
        // ===== 4. 活跃审批单批量预加载 =====
        Map<String, ReceivedApply> activeApplies = loadActiveAppliesBatch(period, bizKeys);
        // ===== 5. 逐订单分流建单 =====
        int created = 0;
        for (Map.Entry<String, List<com.panjia.received.domain.ReceivedDetail>> entry : factsByOrder.entrySet()) {
            String orderNo = entry.getKey();
            List<com.panjia.received.domain.ReceivedDetail> rows = entry.getValue();
            List<Long> factIds = rows.stream().map(com.panjia.received.domain.ReceivedDetail::getId).toList();
            BigDecimal realSum = rows.stream()
                .map(r -> nvlAmount(r.getPerformanceAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            com.panjia.received.domain.ReceivedContract contract = contractByOrder.get(orderNo);
            Long uniqueDeptId = contract == null ? null : contract.getDeptId();
            BigDecimal expectedAmount = expectTotalByOrder.getOrDefault(orderNo, BigDecimal.ZERO);
            try {
                ReceivedApply existing = activeApplies.get(orderNo);
                if (existing != null && existing.getStatus() == ReceivedApplyStatus.APPROVED) {
                    log.info("[实收审批] 贝壳实收订单当月审批单已 APPROVED，跳过：applyId={}, orderNo={}",
                        existing.getId(), orderNo);
                    continue;
                }
                if (existing != null) {
                    // DRAFT/SUBMITTED：整单从严，新事实并入人工审批流程
                    bindFacts(factIds, existing.getId());
                    existing.setItemCount((existing.getItemCount() == null ? 0 : existing.getItemCount())
                        + rows.size());
                    existing.setReceivedAmount(
                        (existing.getReceivedAmount() == null ? BigDecimal.ZERO : existing.getReceivedAmount())
                            .add(realSum));
                    existing.setExpectedAmount(expectedAmount);
                    if (existing.getDeptId() == null) {
                        existing.setDeptId(uniqueDeptId);
                    }
                    if (StringUtils.isBlank(existing.getBizType()) && contract != null) {
                        existing.setBizType(contract.getBizType());
                    }
                    applyMapper.updateById(existing);
                    log.info("[实收审批] 贝壳实收事实合并入既有审批单：applyId={}, orderNo={}",
                        existing.getId(), orderNo);
                    continue;
                }
                ReceivedApply apply = newApplyFromContract(period, contract, batchId, operatorId,
                    rows.size(), realSum, expectedAmount, uniqueDeptId);
                boolean hasNewSign = expectedAmount != null && expectedAmount.signum() > 0;
                if (!hasNewSign) {
                    // 无新签：DRAFT 不启动工作流、不 emit，等新签导入触发（修复原自动通过 bug）
                    insertApply(apply);
                    bindFacts(factIds, apply.getId());
                    log.info("[实收审批] 贝壳实收无新签，DRAFT 待新签触发：applyNo={}, orderNo={}, received={}",
                        apply.getApplyNo(), orderNo, apply.getReceivedAmount());
                    created++;
                    continue;
                }
                // 合同维度判定：订单到账合计 vs 订单跨月新签合计
                boolean needManual = needManualReview(realSum, expectedAmount);
                if (needManual) {
                    insertApply(apply);
                    bindFacts(factIds, apply.getId());
                    startWorkflow(apply, apply.getApplicantId(), Set.of());
                    log.info("[实收审批] 贝壳实收建单并提交人工审批：applyNo={}, orderNo={}, received={}",
                        apply.getApplyNo(), orderNo, apply.getReceivedAmount());
                } else {
                    // 实收金额口径（2026-09-27）：PERF_REAL 保留公司到账金额（可大于新签，
                    // 公司账务用）；结佣金额由结佣域按新签口径取值，此处不再改写事实金额
                    // 整单自动通过：APPROVED 终态直接 INSERT，无流程实例（同历史直建语义）
                    apply.setStatus(ReceivedApplyStatus.APPROVED);
                    apply.setReceivedAmount(realSum);
                    apply.setApproverId(operatorId);
                    apply.setApproveTime(LocalDateTime.now());
                    insertApply(apply);
                    bindFacts(factIds, apply.getId());
                    emitApprovedEvent(apply, operatorId);
                    log.info("[实收审批] 贝壳实收自动通过直建（实收保留到账金额）：applyNo={}, orderNo={}, received={}",
                        apply.getApplyNo(), orderNo, apply.getReceivedAmount());
                }
                created++;
            } catch (DuplicateKeyException e) {
                log.warn("[实收审批] 贝壳实收建单撞唯一索引，跳过：period={}, orderNo={}", period, orderNo);
            }
        }
        log.info("[实收审批] 贝壳实收分流建单完成：batchId={}, period={}, 新建={}", batchId, period, created);
        return created;
    }

    /**
     * 合同维度分流判定：返回 true = 需人工审批。
     * <p>到账合计 ≥ 0：到账 &lt; 订单跨月新签合计 → 人工（≥ 视为到账已覆盖，自动通过）；
     * 到账合计 &lt; 0（事后扣减负到账）：该订单存在正当数新签（负 &lt; 正恒成立）→ 自动通过，
     * 无正当数新签（数据异常）→ 人工。
     */
    private static boolean needManualReview(BigDecimal arrival, BigDecimal expectTotal) {
        BigDecimal amt = arrival == null ? BigDecimal.ZERO : arrival;
        if (amt.signum() >= 0) {
            return amt.compareTo(expectTotal == null ? BigDecimal.ZERO : expectTotal) < 0;
        }
        return expectTotal == null || expectTotal.signum() <= 0;
    }

    /** 发布实收审批通过事件（结佣域按合同自动产生结佣记录，outbox 原子提交）。 */
    private void emitApprovedEvent(ReceivedApply apply, Long operatorId) {
        ReceivedApprovedEvent event = new ReceivedApprovedEvent();
        event.setApplyId(apply.getId());
        event.setPeriod(apply.getPeriod());
        event.setOrderNo(apply.getOrderNo());
        event.setContractNo(apply.getContractNo());
        event.setOperatorId(operatorId);
        eventPort.emit(event);
    }

    private static BigDecimal nvlAmount(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean resolveDraftAfterNewSign(String period, String contractNo, Long operatorId) {
        if (StringUtils.isBlank(period) || StringUtils.isBlank(contractNo)) {
            return false;
        }
        if (operatorId == null) {
            try {
                operatorId = LoginHelper.getUserId();
            } catch (Exception ignored) {
            }
        }
        if (operatorId == null) {
            operatorId = 1L;
        }

        // 1. 查该合同当月 DRAFT 实收审批单（取最新一张）
        ReceivedApply apply = applyMapper.selectOne(new LambdaQueryWrapper<ReceivedApply>()
            .eq(ReceivedApply::getPeriod, period)
            .eq(ReceivedApply::getContractNo, contractNo)
            .eq(ReceivedApply::getStatus, ReceivedApplyStatus.DRAFT)
            .orderByDesc(ReceivedApply::getId)
            .last("LIMIT 1"));
        if (apply == null) {
            return false;
        }

        // 2. 跨月查该订单 PERF_EXPECT ACTIVE 合计（口径同 autoCreateForReceivedBatch）
        BigDecimal expectTotal = sumExpectByOrderNo(apply.getOrderNo());
        if (expectTotal.signum() <= 0) {
            // 仍无新签（兜底，不应发生——新签刚导入触发）
            return false;
        }

        BigDecimal realSum = nvlAmount(apply.getReceivedAmount());
        apply.setExpectedAmount(expectTotal);
        if (realSum.compareTo(expectTotal) >= 0) {
            // 实收 ≥ 新签 → 自动通过 + emit（触发结佣建单）
            apply.setStatus(ReceivedApplyStatus.APPROVED);
            apply.setApproverId(operatorId);
            apply.setApproveTime(LocalDateTime.now());
            applyMapper.updateById(apply);
            emitApprovedEvent(apply, operatorId);
            log.info("[实收审批] 新签触发自动通过：applyNo={}, orderNo={}, received={}, expect={}",
                apply.getApplyNo(), apply.getOrderNo(), realSum, expectTotal);
            return true;
        }
        // 实收 < 新签 → 启动人工审批工作流（与导入时"有新签但不足"路径一致）
        startWorkflow(apply, apply.getApplicantId(), Set.of());
        log.info("[实收审批] 新签触发人工审批（实收不足）：applyNo={}, orderNo={}, received={}, expect={}",
            apply.getApplyNo(), apply.getOrderNo(), realSum, expectTotal);
        return false;
    }

    /**
     * 跨月查该订单 PERF_EXPECT ACTIVE 业绩金额合计（复用 autoCreateForReceivedBatch 口径）。
     */
    private BigDecimal sumExpectByOrderNo(String orderNo) {
        if (StringUtils.isBlank(orderNo)) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (PerformanceFact e : factMapper.selectList(new LambdaQueryWrapper<PerformanceFact>()
                .eq(PerformanceFact::getFactType, FACT_TYPE_EXPECT)
                .eq(PerformanceFact::getFactStatus, com.panjia.performance.domain.FactStatus.ACTIVE)
                .eq(PerformanceFact::getOrderNo, orderNo))) {
            sum = sum.add(nvlAmount(e.getPerformanceAmount()));
        }
        return sum;
    }

    /**
     * 一条 IN 查询批量取指定订单号当月活跃审批单（DRAFT/SUBMITTED/APPROVED），同一订单号取最新一张。
     */
    private Map<String, ReceivedApply> loadActiveAppliesBatch(String period, Collection<String> orderNos) {
        if (orderNos.isEmpty()) {
            return Map.of();
        }
        List<ReceivedApply> applies = applyMapper.selectList(new LambdaQueryWrapper<ReceivedApply>()
            .eq(ReceivedApply::getPeriod, period)
            .in(ReceivedApply::getOrderNo, orderNos)
            .in(ReceivedApply::getStatus,
                ReceivedApplyStatus.DRAFT, ReceivedApplyStatus.SUBMITTED, ReceivedApplyStatus.APPROVED)
            .orderByDesc(ReceivedApply::getId));
        Map<String, ReceivedApply> result = new HashMap<>(orderNos.size() * 2);
        for (ReceivedApply apply : applies) {
            if (apply.getOrderNo() != null) {
                result.putIfAbsent(apply.getOrderNo(), apply);
            }
        }
        return result;
    }

    /** 明细行归属门店去重：恰好一个门店时返回该 ID；明细全空时回退合同级门店；多门店返回 null（跨店合作单留空）。 */
    private Long uniqueDetailDeptId(List<com.panjia.received.domain.ReceivedDetail> rows,
                                    com.panjia.received.domain.ReceivedContract contract) {
        Set<Long> deptIds = new java.util.HashSet<>();
        for (com.panjia.received.domain.ReceivedDetail row : rows) {
            if (row.getDeptId() != null) {
                deptIds.add(row.getDeptId());
            }
        }
        if (deptIds.size() == 1) {
            return deptIds.iterator().next();
        }
        return contract == null ? null : contract.getDeptId();
    }

    // ==================== 驳回重提 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReceivedApply resubmit(Long id) {
        ReceivedApply apply = getAndCheck(id);
        if (apply.getStatus() != ReceivedApplyStatus.REJECTED && apply.getStatus() != ReceivedApplyStatus.DRAFT) {
            throw new ServiceException("仅草稿/已驳回状态的审批单可重新提交（当前：" + apply.getStatus().getDesc() + "）");
        }
        Long operatorId = LoginHelper.getUserId();
        apply.setApplicantId(operatorId);
        apply.setStatus(ReceivedApplyStatus.SUBMITTED);
        applyMapper.updateById(apply);

        if (StringUtils.isBlank(apply.getProcessInstanceId())) {
            startWorkflow(apply, operatorId, currentRoles());
            return apply;
        }
        // 驳回后流程停在申请人节点：办理申请人任务重新提交，并按当前发起人角色路由
        Long taskId = approvalPort.currentTaskId(BizType.REAL_CONFIRM, id);
        if (taskId == null) {
            throw new ServiceException("审批流程任务不存在，请联系管理员");
        }
        approvalPort.completeAsSys(BizType.REAL_CONFIRM, id, ApprovalAction.PASS, "重新提交");
        routeAfterApplicant(apply, currentRoles());
        return apply;
    }

    // ==================== 作废 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id) {
        ReceivedApply apply = getAndCheck(id);
        if (apply.getStatus() != ReceivedApplyStatus.DRAFT && apply.getStatus() != ReceivedApplyStatus.SUBMITTED) {
            throw new ServiceException("仅待提交/审批中的单据可作废（当前：" + apply.getStatus().getDesc() + "）");
        }
        // 解绑该审批单已绑定的实收事实：作废后事实应可重新发起（项目约束"申请单作废时未审批明细必须随单冲销以释放事实可重新发起"）
        unbindFacts(id);
        if (StringUtils.isNotBlank(apply.getProcessInstanceId())) {
            // 终止运行中的流程实例（触发 cancel 事件，监听器幂等置 CANCELLED）
            approvalPort.cancel(BizType.REAL_CONFIRM, id);
            // cancel 事件在同一事务内已用新版本对象置 CANCELLED，重新加载避免 @Version 乐观锁更新丢失
            apply = applyMapper.selectById(id);
            if (apply != null && apply.getStatus() == ReceivedApplyStatus.CANCELLED) {
                return;
            }
        }
        apply.setStatus(ReceivedApplyStatus.CANCELLED);
        apply.setCurrentNode(null);
        applyMapper.updateById(apply);
    }

    /**
     * 解绑审批单下所有事实的 receivedApplyId 关联。
     * <p>用于作废场景：让事实恢复自由身，可重新挂载到新的审批单。
     * <p>幂等：仅当前已绑定到本单的事实会被更新，其他单的事实不受影响。
     * 拆表后 PERF_REAL 已迁出，解绑改查实收明细（见底部 unbindFacts）。
     */

    // ==================== 批量审批（线程池异步 + CompletableFuture 挂起等待） ====================

    @Override
    public CompletableFuture<BatchApproveResultVo> batchApproveByContractAsync(String period, List<String> contractNos) {
        if (StringUtils.isBlank(period)) {
            throw new ServiceException("结算月不能为空");
        }
        if (contractNos == null || contractNos.isEmpty()) {
            throw new ServiceException("合同号列表不能为空");
        }
        LinkedHashSet<String> deduped = new LinkedHashSet<>();
        for (String c : contractNos) {
            if (c != null && !c.trim().isEmpty()) {
                deduped.add(c.trim());
            }
        }
        if (deduped.isEmpty()) {
            throw new ServiceException("合同号列表不能为空");
        }
        Long operatorId;
        String operatorName;
        try {
            operatorId = LoginHelper.getUserId();
            operatorName = LoginHelper.getUsername();
        } catch (Exception e) {
            throw new ServiceException("无法获取当前登录用户信息，请重新登录");
        }
        // 同步阶段过滤：在 HTTP 线程中有 Sa-Token 上下文。
        // ① 一条 IN 查询取所有 SUBMITTED 审批单（替代逐单 selectOne 的 N 次查询）；
        // ② myCurrentTasks 以 3 条 SQL 完成全部单据的待办鉴权（替代逐单 isMyTask 的 3N 条 SQL）。
        BatchApproveResultVo result = new BatchApproveResultVo();
        result.setTotal(deduped.size());
        Map<String, ReceivedApply> submittedApplies = loadSubmittedAppliesBatch(period, deduped);
        List<ReceivedApply> applyList = submittedApplies.values().stream().distinct().toList();
        Map<Long, MyTaskBrief> myTaskMap = applyList.isEmpty()
            ? Map.of()
            : approvalPort.myCurrentTasks(BizType.REAL_CONFIRM,
                applyList.stream().map(ReceivedApply::getId).toList());
        // 按用户输入顺序组装可办任务，异步直接用 taskId 办理
        List<RcvApproveItem> approveItems = new ArrayList<>(deduped.size());
        for (String contractNo : deduped) {
            ReceivedApply apply = submittedApplies.get(contractNo);
            if (apply == null || myTaskMap.get(apply.getId()) == null) {
                result.getSkippedContracts().add(contractNo);
                continue;
            }
            approveItems.add(new RcvApproveItem(contractNo, apply, myTaskMap.get(apply.getId())));
        }
        result.setSkipped(result.getSkippedContracts().size());
        result.setFailed(result.getFailedContracts().size());
        log.info("[实收审批] 批量审批已提交：period={}, total={}, myTasks={}, skipped={}, operator={}",
            period, deduped.size(), approveItems.size(), result.getSkipped(), operatorName);
        final BatchApproveResultVo syncResult = result;
        return CompletableFuture.supplyAsync(
            () -> {
                BatchApproveResultVo asyncResult = doBatchApprove(approveItems, operatorId, operatorName);
                // 合并同步阶段已跳过/失败的
                syncResult.getSkippedContracts().forEach(asyncResult.getSkippedContracts()::add);
                syncResult.getFailedContracts().forEach(asyncResult.getFailedContracts()::add);
                asyncResult.setTotal(syncResult.getTotal());
                asyncResult.setSkipped(asyncResult.getSkippedContracts().size());
                asyncResult.setFailed(asyncResult.getFailedContracts().size());
                return asyncResult;
            }, taskExecutor);
    }

    /**
     * 批量审批预检项：输入合同号 + 审批单 + 当前用户可办任务。
     */
    private record RcvApproveItem(String contractNo, ReceivedApply apply, MyTaskBrief task) {
    }

    /**
     * 一条 IN 查询批量取指定合同当月 SUBMITTED 实收审批单（批量审批预检用）。
     * <p>按输入字符串（合同号或订单号）双键建映射，同一合同取 ID 最大（最新）一张。
     */
    private Map<String, ReceivedApply> loadSubmittedAppliesBatch(String period, Collection<String> contractNos) {
        if (contractNos.isEmpty()) {
            return Map.of();
        }
        List<ReceivedApply> applies = applyMapper.selectList(new LambdaQueryWrapper<ReceivedApply>()
            .eq(ReceivedApply::getPeriod, period)
            .and(w -> w.in(ReceivedApply::getContractNo, contractNos)
                .or().in(ReceivedApply::getOrderNo, contractNos))
            .eq(ReceivedApply::getStatus, ReceivedApplyStatus.SUBMITTED)
            .orderByDesc(ReceivedApply::getId));
        Map<String, ReceivedApply> byContract = new HashMap<>();
        Map<String, ReceivedApply> byOrder = new HashMap<>();
        for (ReceivedApply apply : applies) {
            if (apply.getContractNo() != null) {
                byContract.putIfAbsent(apply.getContractNo(), apply);
            }
            if (apply.getOrderNo() != null) {
                byOrder.putIfAbsent(apply.getOrderNo(), apply);
            }
        }
        Map<String, ReceivedApply> resultMap = new HashMap<>(contractNos.size() * 2);
        for (String input : contractNos) {
            ReceivedApply apply = byContract.getOrDefault(input, byOrder.get(input));
            if (apply != null) {
                resultMap.put(input, apply);
            }
        }
        return resultMap;
    }

    /**
     * 逐单办理（TaskExecutor 线程池执行，CompletableFuture 供应方）。
     * 审批单与当前待办任务均由同步阶段预检透传，异步不再查审批单/当前任务；
     * 用 completeTaskAsSys 按 taskId 办理。预检后任务若被他人抢先办理，
     * 引擎抛异常计入失败（并发安全）。单据失败不中断整批。
     * <p>
     * 多线程分片：合同之间无交集，按 50 个一组分片，每片一个线程并行办理。
     */
    private BatchApproveResultVo doBatchApprove(List<RcvApproveItem> items,
                                                  Long operatorId, String operatorName) {
        BatchApproveResultVo result = new BatchApproveResultVo();
        result.setTotal(items.size());
        if (items.isEmpty()) {
            return result;
        }
        // 按 50 个一组分片
        int chunkSize = 50;
        int chunkCount = (items.size() + chunkSize - 1) / chunkSize;
        List<List<RcvApproveItem>> chunks = new ArrayList<>(chunkCount);
        for (int i = 0; i < items.size(); i += chunkSize) {
            chunks.add(items.subList(i, Math.min(i + chunkSize, items.size())));
        }
        // 并行处理各分片，收集结果
        List<CompletableFuture<BatchApproveResultVo>> futures = chunks.stream()
            .map(chunk -> CompletableFuture.supplyAsync(
                () -> doBatchApproveChunk(chunk, operatorId, operatorName), taskExecutor))
            .toList();
        // 合并各分片结果
        for (CompletableFuture<BatchApproveResultVo> f : futures) {
            try {
                BatchApproveResultVo chunkResult = f.join();
                result.getSuccessContracts().addAll(chunkResult.getSuccessContracts());
                result.getFailedContracts().addAll(chunkResult.getFailedContracts());
            } catch (Exception e) {
                log.error("[实收审批] 分片处理异常", e);
            }
        }
        result.setSuccess(result.getSuccessContracts().size());
        result.setSkipped(result.getSkippedContracts().size());
        result.setFailed(result.getFailedContracts().size());
        log.info("[实收审批] 批量审批完成：分片={}, 成功={}, 跳过={}, 失败={}",
            chunkCount, result.getSuccess(), result.getSkipped(), result.getFailed());
        return result;
    }

    /** 单分片处理（一个线程内逐单办理） */
    private BatchApproveResultVo doBatchApproveChunk(List<RcvApproveItem> items,
                                                     Long operatorId, String operatorName) {
        BatchApproveResultVo result = new BatchApproveResultVo();
        for (RcvApproveItem item : items) {
            try {
                String approver = operatorName != null ? operatorName : String.valueOf(operatorId);
                approvalPort.completeTaskAsSys(item.task().getTaskId(),
                    "批量审批通过（操作人：" + approver + "）");
                result.getSuccessContracts().add(item.contractNo());
            } catch (Exception e) {
                result.getFailedContracts().add(item.contractNo());
                log.warn("[实收审批] 批量审批单据失败：contractNo={}, reason={}",
                    item.contractNo(), e.getMessage());
            }
        }
        return result;
    }

    // ==================== 工作流回调 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleWorkflowEvent(Long applyId, String status, String handler, String message) {
        ReceivedApply apply = applyMapper.selectById(applyId);
        if (apply == null) {
            log.warn("[实收审批工作流] 单据不存在，忽略：applyId={}, status={}", applyId, status);
            return;
        }
        Long handlerId = parseHandlerId(handler);
        switch (status == null ? "" : status) {
            case "finish" -> {
                if (apply.getStatus() == ReceivedApplyStatus.APPROVED) {
                    return;
                }
                apply.setStatus(ReceivedApplyStatus.APPROVED);
                apply.setCurrentNode(null);
                apply.setApproverId(handlerId);
                apply.setApproveTime(LocalDateTime.now());
                applyMapper.updateById(apply);
                // 人工审批通过：同样发布实收审批通过事件（结佣域按合同自动产生结佣记录）
                emitApprovedEvent(apply, handlerId);
                log.info("[实收审批工作流] 审批通过：applyId={}, handler={}", applyId, handler);
            }
            case "back" -> {
                apply.setStatus(ReceivedApplyStatus.REJECTED);
                apply.setCurrentNode(null);
                // 与调整单口径一致：驳回也留痕审批人/审批时间
                apply.setApproverId(handlerId);
                apply.setApproveTime(LocalDateTime.now());
                applyMapper.updateById(apply);
                log.info("[实收审批工作流] 驳回：applyId={}, handler={}, message={}", applyId, handler, message);
            }
            case "cancel" -> {
                apply.setStatus(ReceivedApplyStatus.CANCELLED);
                apply.setCurrentNode(null);
                applyMapper.updateById(apply);
                log.info("[实收审批工作流] 撤销：applyId={}", applyId);
            }
            case "invalid", "termination" -> {
                apply.setStatus(ReceivedApplyStatus.CANCELLED);
                apply.setCurrentNode(null);
                applyMapper.updateById(apply);
                log.info("[实收审批工作流] 作废/终止：applyId={}", applyId);
            }
            default -> log.info("[实收审批工作流] 忽略状态：applyId={}, status={}", applyId, status);
        }
    }

    /**
     * 流程进入总监节点 = 财务节点已办理完成：回填最近审批人/审批时间。
     * <p>覆盖「我的待办 → 去处理 → 通过」的原生 completeTask 路径（不经过业务 approve 入口），
     * 否则财务审批后、总监终审前，审批单上的审批人/审批时间为空。
     * <p>幂等：非审批中状态直接跳过；重放时重复写入相同值无副作用。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void stampApproverOnDirectorNode(Long applyId, Long handlerId) {
        if (applyId == null) {
            return;
        }
        ReceivedApply apply = applyMapper.selectById(applyId);
        if (apply == null || apply.getStatus() != ReceivedApplyStatus.SUBMITTED) {
            return;
        }
        if (handlerId != null) {
            apply.setApproverId(handlerId);
            apply.setApproveTime(LocalDateTime.now());
        }
        refreshCurrentNode(apply);
        log.info("[实收审批工作流] 财务已通过，回填审批人留痕：applyId={}, handlerId={}", applyId, handlerId);
    }

    // ==================== 查询 ====================

    @Override
    public PageResult<ReceivedApply> list(ReceivedApplyBo query, PageQuery pageQuery) {
        // §3.6 数据权限：所有登录用户仅本部门（含下级）审批单（统一走 DeptScopeUtils，超管不限）
        Long effectiveDeptId = DeptScopeUtils.enforceSelfDeptScope(query == null ? null : query.getDeptId(), deptService::selectDeptAndChildById, "实收审批");
        // 默认排除已作废（CANCELLED），与业绩明细只查 ACTIVE 一致；
        // 前端显式传 status 时按指定状态查询（含 CANCELLED）
        LambdaQueryWrapper<ReceivedApply> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.isNotBlank(query.getPeriod()), ReceivedApply::getPeriod, query.getPeriod())
            .eq(query.getBatchId() != null, ReceivedApply::getBatchId, query.getBatchId())
            // 业务类型已落库（biz_type，见 V140009）：筛选下推到 SQL（旧实现在接口层无谓传入后被静默忽略）
            .eq(StringUtils.isNotBlank(query.getBizType()), ReceivedApply::getBizType, query.getBizType())
            .ne(StringUtils.isBlank(query.getStatus()),
                ReceivedApply::getStatus, ReceivedApplyStatus.CANCELLED)
            .eq(StringUtils.isNotBlank(query.getStatus()),
                ReceivedApply::getStatus, ReceivedApplyStatus.fromCode(query.getStatus()))
            .and(StringUtils.isNotBlank(query.getKeyword()), w -> w
                .like(ReceivedApply::getContractNo, query.getKeyword())
                .or().like(ReceivedApply::getOrderNo, query.getKeyword())
                .or().like(ReceivedApply::getPropertyAddress, query.getKeyword()))
            // 三个业绩列表统一排序：签约/认购时间倒序 → 订单号(空取合同号)次序 → id 倒序兜底，
            // 不再按建单时间排（批量导入时建单时间集中且与业务发生顺序无关）
            .orderByDesc(ReceivedApply::getBusinessDate)
            .last(", COALESCE(order_no, contract_no), id DESC");
        // 审批节点数据隔离：审批中单据只允许本人角色对应节点可见（前端不再传节点参数，防绕过由服务端强制）
        applyApprovalNodeScope(wrapper);
        // 门店/组别筛选：通过实收明细关联员工归属部门过滤（合同下人员可能跨部门，不能用审批单的单一 dept_id）。
        // 拆表后 PERF_REAL 已迁出 pj_perf_fact → pj_received_detail；导入时 employee_id 暂留空、
        // 只存 employee_external_code，故须按外部工号关联 pj_people_employee 取 dept_id
        // （兼容后续 employee_id 回填：优先 ID 直连，为空时按工号匹配）。
        if (effectiveDeptId != null) {
            wrapper.and(w -> w.apply(
                "EXISTS (SELECT 1 FROM pj_received_detail rd"
                    + " JOIN pj_people_employee e ON (e.employee_id = rd.employee_id"
                    + "   OR (rd.employee_id IS NULL AND e.employee_code = rd.employee_external_code))"
                    + " WHERE rd.received_apply_id = pj_perf_received_apply.id"
                    + " AND rd.detail_status = 'ACTIVE'"
                    + " AND (e.dept_id = {0}"
                    + " OR e.dept_id IN (SELECT sd.dept_id FROM sys_dept sd"
                    + " WHERE sd.ancestors LIKE CONCAT('%', {0}, '%'))))",
                effectiveDeptId));
        }
        // 员工筛选：通过实收明细关联员工（口径同上，employee_id 未回填时按外部工号匹配）
        if (query.getEmployeeId() != null) {
            wrapper.and(w -> w.apply(
                "EXISTS (SELECT 1 FROM pj_received_detail rd"
                    + " JOIN pj_people_employee e ON (e.employee_id = rd.employee_id"
                    + "   OR (rd.employee_id IS NULL AND e.employee_code = rd.employee_external_code))"
                    + " WHERE rd.received_apply_id = pj_perf_received_apply.id"
                    + " AND rd.detail_status = 'ACTIVE'"
                    + " AND e.employee_id = {0})",
                query.getEmployeeId()));
        }
        Page<ReceivedApply> page = applyMapper.selectPage(pageQuery.build(), wrapper);
        List<ReceivedApply> records = page.getRecords();
        fillContractMetrics(records);
        return PageResult.build(records, page.getTotal());
    }

    /**
     * 回填实收明细列表的补充字段（涉及人数、应收合计）。
     * <p>
     * 业务类型已落库（{@code biz_type}，见 V140009），本方法只在旧数据 bizType 为空时
     * 按 (period, contractNo) 从 ACTIVE 事实回退补齐；涉及人数与应收合计仍需实时聚合，
     * 口径与详情弹窗「每人实收明细」一致；按期间分组批量查询，避免 N+1。
     * 应收合计含已生效调整（新签业绩显示调整后金额），与快照不一致时置「已调整」标记。
     * 期间或合同号缺失的行保持 null，前端显示占位符。
     */
    private void fillContractMetrics(List<ReceivedApply> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Map<String, Set<String>> contractsByPeriod = new HashMap<>();
        for (ReceivedApply apply : records) {
            if (StringUtils.isBlank(apply.getPeriod()) || StringUtils.isBlank(apply.getContractNo())) {
                continue;
            }
            contractsByPeriod.computeIfAbsent(apply.getPeriod(), k -> new LinkedHashSet<>())
                .add(apply.getContractNo());
        }
        if (contractsByPeriod.isEmpty()) {
            return;
        }
        Map<String, ReceivedContractMetricsVo> metrics = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : contractsByPeriod.entrySet()) {
            List<ReceivedContractMetricsVo> rows =
                factMapper.selectReceivedContractMetrics(entry.getKey(), entry.getValue());
            for (ReceivedContractMetricsVo row : rows) {
                metrics.put(metricsKey(entry.getKey(), row.getContractNo()), row);
            }
        }
        // 一次性批量取本页全部 bizType 的折算因子（避免循环内逐条 factorOf(String) 触发全表扫描 pj_payroll_conversion_rule）
        Set<String> bizTypes = metrics.values().stream()
            .map(ReceivedContractMetricsVo::getBizType)
            .filter(StringUtils::isNotBlank)
            .collect(java.util.stream.Collectors.toSet());
        Map<String, BigDecimal> factorMap = conversionFactorPort.factorsOf(bizTypes);
        for (ReceivedApply apply : records) {
            ReceivedContractMetricsVo m = metrics.get(metricsKey(apply.getPeriod(), apply.getContractNo()));
            if (m != null) {
                // 业务类型优先用落库快照值；旧数据（列新增前建单）为空时回退实时聚合
                String bizType = StringUtils.isBlank(apply.getBizType()) ? m.getBizType() : apply.getBizType();
                apply.setBizType(bizType);
                apply.setEmployeeCount(m.getEmployeeCount());
                // 新签业绩展示实时值（含已生效调整），与详情/每人明细口径一致；与快照不一致时标「已调整」
                if (m.getExpectedAmount() != null) {
                    // 快照即「调整前」值，先留存再覆盖为实时值（口径同 getDetail），供前端展示「原值 → 调整后值」
                    apply.setOriginalExpectedAmount(apply.getExpectedAmount());
                    apply.setExpectedAdjusted(apply.getExpectedAmount() != null
                        && apply.getExpectedAmount().compareTo(m.getExpectedAmount()) != 0);
                    apply.setExpectedAmount(m.getExpectedAmount());
                }
                // 实收业绩展示实时值（ACTIVE PERF_REAL 合计，含已生效结佣调整）；
                // 事实链最早值留存为「调整前」，与实时值不一致时置「已调整」，供前端展示「原值 → 调整后值」
                if (m.getReceivedAmount() != null) {
                    boolean receivedAdjusted = m.getOriginalReceivedAmount() != null
                        && m.getOriginalReceivedAmount().compareTo(m.getReceivedAmount()) != 0;
                    apply.setReceivedAdjusted(receivedAdjusted);
                    if (receivedAdjusted) {
                        apply.setOriginalReceivedAmount(m.getOriginalReceivedAmount());
                    }
                    apply.setReceivedAmount(m.getReceivedAmount());
                }
                // 应收折算后金额：按业务类型因子从批量结果中取（实收已不做折算）
                BigDecimal factor = conversionFactorPort.factorOf(factorMap, bizType);
                if (m.getExpectedAmount() != null) {
                    apply.setExpectedConvertedAmount(conversionFactorPort.convert(m.getExpectedAmount(), factor));
                    if (apply.getOriginalExpectedAmount() != null) {
                        apply.setOriginalExpectedConvertedAmount(
                            conversionFactorPort.convert(apply.getOriginalExpectedAmount(), factor));
                    }
                }
            }
        }
    }

    /** 组装 (期间, 合同号) 复合键；任一为空返回空串（对应查不到，保持 null）。 */
    private String metricsKey(String period, String contractNo) {
        return StringUtils.isBlank(period) || StringUtils.isBlank(contractNo) ? "" : period + '|' + contractNo;
    }

    @Override
    public ReceivedApplyDetail getDetail(Long id) {
        ReceivedApply apply = getAndCheck(id);
        // 应收是参照口径（非审批对象）：展示时实时取当前 ACTIVE PERF_EXPECT 合计（含已生效调整），
        // 与明细行「按 source_key 实时配对」口径一致；仅内存覆盖，不落库。
        // 与提交时快照不一致时置「已调整」标记，让业务人员知道差额来自业绩调整。
        if (StringUtils.isNotBlank(apply.getContractNo())) {
            BigDecimal expected = sumExpect(apply.getPeriod(), apply.getContractNo());
            apply.setExpectedAdjusted(apply.getExpectedAmount() != null
                && apply.getExpectedAmount().compareTo(expected) != 0);
            // 保留快照供前端展示「调整前」，再覆盖为当前值
            apply.setOriginalExpectedAmount(apply.getExpectedAmount());
            apply.setExpectedAmount(expected);
        }
        List<ReceivedFactDetailVo> facts = factMapper.selectReceivedFactDetails(
            apply.getPeriod(), apply.getContractNo());
        // 实收不再做折算；应收折算因子按合同业务类型取（整单同一因子）
        String bizType = apply.getBizType();
        Map<String, BigDecimal> factorMap = conversionFactorPort.factorsOf(
            bizType == null ? java.util.Collections.emptySet() : java.util.Collections.singleton(bizType));
        BigDecimal factor = conversionFactorPort.factorOf(factorMap, bizType);
        BigDecimal recvSum = BigDecimal.ZERO;
        BigDecimal expectConvertedSum = BigDecimal.ZERO;
        BigDecimal expectOriginalConvertedSum = BigDecimal.ZERO;
        for (ReceivedFactDetailVo f : facts) {
            // 应收折算（实收折算已取消）
            f.setExpectedConvertedAmount(conversionFactorPort.convert(f.getExpectedAmount(), factor));
            // 调整前应收的折算后金额：与当前值同一因子，仅在原值存在时输出（无调整则与原值一致，前端不展示）
            if (f.getOriginalExpectedAmount() != null) {
                f.setOriginalConvertedAmount(conversionFactorPort.convert(f.getOriginalExpectedAmount(), factor));
            }
            if (f.getAmount() != null) recvSum = recvSum.add(f.getAmount());
            if (f.getExpectedConvertedAmount() != null) expectConvertedSum = expectConvertedSum.add(f.getExpectedConvertedAmount());
            if (f.getOriginalConvertedAmount() != null) expectOriginalConvertedSum = expectOriginalConvertedSum.add(f.getOriginalConvertedAmount());
        }
        // 实收合计取实收明细实时合计（rd ACTIVE）；实收侧暂无调整链，不展示「原值 → 调整后值」，也不再折算
        apply.setReceivedAmount(recvSum);
        apply.setReceivedAdjusted(false);
        apply.setExpectedConvertedAmount(expectConvertedSum);
        // 合计口径与明细列一致：调整前应收折算合计（供详情「应收合计」展示「原值 → 调整后值」）
        apply.setOriginalExpectedConvertedAmount(expectOriginalConvertedSum);
        return new ReceivedApplyDetail(apply, facts);
    }

    @Override
    public Long getInstanceId(Long id) {
        return approvalPort.instanceId(BizType.REAL_CONFIRM, id);
    }

    // ==================== 内部方法 ====================

    /**
     * 构建审批启动命令（业务编码/标题 + 办理人 + 流程变量），供适配器转译为引擎原生 StartProcessDTO + bizExt。
     */
    private ApprovalStartCmd buildStartCmd(ReceivedApply apply) {
        ApprovalStartCmd cmd = ApprovalStartCmd.of(
            text(apply.getApplyNo()),
            "实收审批｜" + text(apply.getContractNo())
                + " " + text(apply.getPropertyAddress())
                + "｜账期" + text(apply.getPeriod())
                + "｜实收" + text(apply.getReceivedAmount()));
        return cmd;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 发起 perf_received 流程并按发起人角色/配置做节点路由（§2.2）。
     */
    private void startWorkflow(ReceivedApply apply, Long applicantId, Set<String> roles) {
        apply.setStatus(ReceivedApplyStatus.SUBMITTED);
        applyMapper.updateById(apply);

        // 确保有有效的发起人：事件驱动场景（导入归档自动建单）无登录上下文，
        // applicantId 可能为 null，需兜底取登录用户，仍为空则用 apply.applicantId
        Long initiator = applicantId;
        if (initiator == null) {
            try {
                initiator = LoginHelper.getUserId();
            } catch (Exception ignored) {
            }
        }
        if (initiator == null) {
            initiator = apply.getApplicantId();
        }
        if (initiator == null) {
            initiator = 1L;
        }

        ApprovalStartCmd cmd = buildStartCmd(apply);
        cmd.setHandler(String.valueOf(initiator));
        Map<String, Object> variables = new HashMap<>(4);
        variables.put("ignore", true);
        variables.put("initiator", String.valueOf(initiator));
        variables.put("initiatorDeptId", apply.getDeptId());
        cmd.setVariables(variables);
        try {
            boolean ok = approvalPort.startAndCompleteFirst(BizType.REAL_CONFIRM, apply.getId(), cmd);
            if (!ok) {
                throw new ServiceException("实收审批流程发起失败");
            }
        } catch (Exception e) {
            log.error("[实收审批] 流程发起异常：applyId={}", apply.getId(), e);
            throw new ServiceException("实收审批流程发起失败：{}", e.getMessage());
        }
        Long instanceId = approvalPort.instanceId(BizType.REAL_CONFIRM, apply.getId());
        if (instanceId != null) {
            apply.setProcessInstanceId(String.valueOf(instanceId));
            applyMapper.updateById(apply);
        }
        routeAfterApplicant(apply, roles);
    }

    /**
     * 申请人首节点办理后的自动路由：
     * skip_finance 或发起人=财务/总监 → 系统自动过财务；发起人=总监 → 继续自动过总监直至完成。
     */
    private void routeAfterApplicant(ReceivedApply apply, Set<String> roles) {
        boolean director = roles != null && roles.contains("director");
        boolean finance = roles != null && roles.contains("finance");
        boolean skipFinance = Boolean.TRUE.equals(configService.getConfigBool(CONFIG_SKIP_FINANCE));

        // 财务节点：财务本人发起 / 总监发起 / 全局跳过财务 → 系统自动办理
        if (director || finance || skipFinance) {
            Long financeTask = taskAtNode(apply.getId(), NODE_FINANCE);
            if (financeTask != null) {
                approvalPort.completeAsSys(BizType.REAL_CONFIRM, apply.getId(), ApprovalAction.PASS,
                    director ? "总监发起，系统自动流转" : "财务发起，系统自动流转");
            }
        }
        // 总监发起：总监节点系统自动办理 → 流程完成（finish 事件置 APPROVED）
        if (director) {
            Long directorTask = taskAtNode(apply.getId(), NODE_DIRECTOR);
            if (directorTask != null) {
                approvalPort.completeAsSys(BizType.REAL_CONFIRM, apply.getId(), ApprovalAction.PASS,
                    "总监发起，系统自动审批通过");
            }
        }
        refreshCurrentNode(apply);
    }

    private Long taskAtNode(Long applyId, String nodeCode) {
        String current = approvalPort.currentNodeCode(BizType.REAL_CONFIRM, applyId);
        return nodeCode.equals(current) ? approvalPort.currentTaskId(BizType.REAL_CONFIRM, applyId) : null;
    }

    /** 从工作流回写当前节点（rcv_finance→FINANCE / rcv_director→DIRECTOR / 已结束→null）。 */
    private void refreshCurrentNode(ReceivedApply apply) {
        String nodeCode = approvalPort.currentNodeCode(BizType.REAL_CONFIRM, apply.getId());
        String shortNode;
        if (NODE_FINANCE.equals(nodeCode)) {
            shortNode = "FINANCE";
        } else if (NODE_DIRECTOR.equals(nodeCode)) {
            shortNode = "DIRECTOR";
        } else {
            shortNode = null;
        }
        apply.setCurrentNode(shortNode);
        applyMapper.updateById(apply);
    }

    private void insertApply(ReceivedApply apply) {
        try {
            applyMapper.insert(apply);
        } catch (DuplicateKeyException e) {
            throw new ServiceException("合同 " + apply.getContractNo() + " " + apply.getPeriod()
                + " 月已存在未完结实收审批单，请刷新");
        }
    }

    private void bindFacts(List<Long> detailIds, Long applyId) {
        if (detailIds.isEmpty()) {
            return;
        }
        // 拆表后 PERF_REAL 已迁出 → 绑定到实收明细表
        detailMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.panjia.received.domain.ReceivedDetail>()
            .in(com.panjia.received.domain.ReceivedDetail::getId, detailIds)
            .set(com.panjia.received.domain.ReceivedDetail::getReceivedApplyId, applyId));
        // 同时更新关联合同的 received_apply_id（便于结佣/查询关联）
        List<Long> contractIds = detailMapper.selectBatchIds(detailIds).stream()
            .map(com.panjia.received.domain.ReceivedDetail::getContractId).distinct().toList();
        if (!contractIds.isEmpty()) {
            contractMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.panjia.received.domain.ReceivedContract>()
                .in(com.panjia.received.domain.ReceivedContract::getId, contractIds)
                .set(com.panjia.received.domain.ReceivedContract::getReceivedApplyId, applyId));
        }
    }

    private void unbindFacts(Long applyId) {
        detailMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.panjia.received.domain.ReceivedDetail>()
            .eq(com.panjia.received.domain.ReceivedDetail::getReceivedApplyId, applyId)
            .set(com.panjia.received.domain.ReceivedDetail::getReceivedApplyId, null));
        contractMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.panjia.received.domain.ReceivedContract>()
            .eq(com.panjia.received.domain.ReceivedContract::getReceivedApplyId, applyId)
            .set(com.panjia.received.domain.ReceivedContract::getReceivedApplyId, null));
        log.info("[实收审批] 已解绑审批单实收明细：applyId={}", applyId);
    }

    /** 按已绑定实收明细重算实收合计/条数，并刷新应收合计与快照。 */
    private void refreshTotals(ReceivedApply apply) {
        List<com.panjia.received.domain.ReceivedDetail> bound = detailMapper.selectList(
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
                .eq(com.panjia.received.domain.ReceivedDetail::getReceivedApplyId, apply.getId())
                .eq(com.panjia.received.domain.ReceivedDetail::getDetailStatus, "ACTIVE"));
        apply.setItemCount(bound.size());
        apply.setReceivedAmount(bound.stream()
            .map(d -> d.getPerformanceAmount() != null ? d.getPerformanceAmount() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add));
        // PERF_EXPECT 应收合计（从 pj_perf_fact 查，跨月）
        Map<String, BigDecimal> expectedMap = factMapper.selectExpectSumsByBizKeys(apply.getPeriod(), List.of(apply.getOrderNo()))
            .stream()
            .collect(Collectors.toMap(BatchFactBindRow::getBizKey,
                r -> r.getAmount() == null ? BigDecimal.ZERO : r.getAmount(), (a, b) -> a));
        apply.setExpectedAmount(expectedMap.getOrDefault(apply.getOrderNo(), BigDecimal.ZERO));
        apply.setUpdateTime(LocalDateTime.now());
        applyMapper.updateById(apply);
    }

    /** 按已绑定实收明细重算实收合计/条数，并刷新应收合计与快照。
     * （已重写为查实收表，旧版查 PERF_REAL 的方法已删除） */

    private BigDecimal sumExpect(String period, String contractNo) {
        return factMapper.selectActiveFactsByContractNo(period, FACT_TYPE_EXPECT, contractNo).stream()
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private ReceivedApply findActiveApply(String period, String contractNo) {
        // 查未完结单（DRAFT/SUBMITTED/APPROVED）：APPROVED 单仍存在以便 autoCreateForBatch 判重跳过，
        // 但 autoCreateForBatch 的 else 分支会对 APPROVED 单跳过合并事实，避免改 receivedAmount。
        return applyMapper.selectOne(new LambdaQueryWrapper<ReceivedApply>()
            .eq(ReceivedApply::getPeriod, period)
            .and(w -> w.eq(ReceivedApply::getContractNo, contractNo)
                .or().eq(ReceivedApply::getOrderNo, contractNo))
            .in(ReceivedApply::getStatus,
                ReceivedApplyStatus.DRAFT, ReceivedApplyStatus.SUBMITTED, ReceivedApplyStatus.APPROVED)
            .orderByDesc(ReceivedApply::getId)
            .last("LIMIT 1"));
    }

    private ReceivedApply getAndCheck(Long id) {
        ReceivedApply apply = applyMapper.selectById(id);
        if (apply == null) {
            throw new ServiceException("实收审批单不存在：" + id);
        }
        return apply;
    }

    /**
     * 审批节点数据隔离（列表查询用）。
     * <p>
     * 审批中（SUBMITTED）单据只允许本人角色对应节点可见：
     * 财务 → FINANCE，总监 → DIRECTOR；兼有两角色则两个节点均可见；
     * 无审批角色者看不到任何审批中单据。非审批中（草稿/已通过/已驳回/已作废）不受限制。
     * 超管看全部。
     */
    private void applyApprovalNodeScope(LambdaQueryWrapper<ReceivedApply> wrapper) {
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        List<String> myNodes = new ArrayList<>();
        try {
            if (LoginHelper.isLogin() && LoginHelper.getLoginUser() != null) {
                Set<String> roles = LoginHelper.getLoginUser().getRolePermission();
                if (roles != null) {
                    if (roles.contains("finance")) {
                        myNodes.add("FINANCE");
                    }
                    if (roles.contains("director")) {
                        myNodes.add("DIRECTOR");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[实收审批] 无登录上下文，不授予任何审批节点可见性：{}", e.getMessage());
        }
        if (myNodes.isEmpty()) {
            wrapper.ne(ReceivedApply::getStatus, ReceivedApplyStatus.SUBMITTED);
        } else {
            wrapper.and(w -> w.ne(ReceivedApply::getStatus, ReceivedApplyStatus.SUBMITTED)
                .or().in(ReceivedApply::getCurrentNode, myNodes));
        }
    }

    private Set<String> currentRoles() {
        try {
            if (LoginHelper.isLogin() && LoginHelper.getLoginUser() != null) {
                Set<String> roles = LoginHelper.getLoginUser().getRolePermission();
                if (LoginHelper.isSuperAdmin()) {
                    roles = roles == null ? new java.util.HashSet<>() : new java.util.HashSet<>(roles);
                    roles.add("director");
                }
                return roles == null ? Set.of() : roles;
            }
        } catch (Exception e) {
            log.debug("[实收审批] 无登录上下文，按系统发起人路由：{}", e.getMessage());
        }
        return Set.of();
    }

    private Long parseHandlerId(String handler) {
        if (StringUtils.isBlank(handler)) {
            return null;
        }
        try {
            return Long.valueOf(handler.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ========== ReceivedApplyPort 端口实现（跨域委托） ==========
    // autoCreateForBatch / autoCreateApprovedForBatch / autoCreateForReceivedBatch
    // 签名与 IReceivedApplyService 完全一致，Java 多接口同签名方法只实现一次。

    @Override
    public int createApplyForRealFacts(Collection<Long> factIds, String period, Long operatorId, Long batchId) {
        if (factIds == null || factIds.isEmpty()) {
            return 0;
        }
        // 拆表后 factId 即 pj_received_detail.id：加载 rd + 批量 JOIN rc 映射成内存事实再建单
        List<com.panjia.received.domain.ReceivedDetail> details = detailMapper.selectBatchIds(factIds);
        if (details.isEmpty()) {
            return 0;
        }
        List<Long> contractIds = details.stream()
            .map(com.panjia.received.domain.ReceivedDetail::getContractId).distinct().toList();
        Map<Long, com.panjia.received.domain.ReceivedContract> contractMap = contractMapper.selectBatchIds(contractIds)
            .stream().collect(Collectors.toMap(com.panjia.received.domain.ReceivedContract::getId, c -> c));
        List<PerformanceFact> realFacts = mapDetailsToFacts(details, contractMap, batchId);
        return createApplyForRealFacts(realFacts, period, operatorId, batchId);
    }

    /** 手工实收镜像合同来源类型（rc.source_type），与导入 KE_RECEIVED/HISTORY_PAYROLL 区分 */
    private static final String SOURCE_TYPE_MANUAL = "MANUAL";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public com.panjia.contracts.dto.ManualReceivedSubmitResultDTO manualSubmitReceived(
            Collection<String> bizKeys, String period, Long operatorId) {
        com.panjia.contracts.dto.ManualReceivedSubmitResultDTO result =
            new com.panjia.contracts.dto.ManualReceivedSubmitResultDTO();
        if (bizKeys == null || bizKeys.isEmpty() || StringUtils.isBlank(period)) {
            return result;
        }
        Long effectiveOperator = operatorId;
        if (effectiveOperator == null) {
            try {
                effectiveOperator = LoginHelper.getUserId();
            } catch (Exception ignored) {
            }
        }
        if (effectiveOperator == null) {
            effectiveOperator = 1L;
        }
        // ① 查 PERF_EXPECT ACTIVE（按期间 + 订单号/合同号），口径同原 PerformanceEngine 手工入口
        List<PerformanceFact> expects = factMapper.selectList(new LambdaQueryWrapper<PerformanceFact>()
            .eq(PerformanceFact::getFactType, FactType.PERF_EXPECT)
            .eq(PerformanceFact::getFactStatus, com.panjia.performance.domain.FactStatus.ACTIVE)
            .eq(PerformanceFact::getPeriod, period)
            .and(w -> w.in(PerformanceFact::getOrderNo, bizKeys)
                .or().in(PerformanceFact::getContractNo, bizKeys))
            .orderByAsc(PerformanceFact::getId));
        if (expects.isEmpty()) {
            return result;
        }
        // ② 逐条镜像造 rc(source_type=MANUAL) + rd（幂等：同 sourceKey ACTIVE rd 已存在则跳过）
        List<com.panjia.received.domain.ReceivedDetail> createdDetails = new ArrayList<>();
        Map<Long, com.panjia.received.domain.ReceivedContract> touchedContractMap = new LinkedHashMap<>();
        for (PerformanceFact expect : expects) {
            if (StringUtils.isBlank(expect.getOrderNo())) {
                result.getSkippedReasons().put(expect.getId(), "应收事实无订单号，无法手工提交实收");
                continue;
            }
            Long existing = detailMapper.selectCount(new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
                .eq(com.panjia.received.domain.ReceivedDetail::getSourceKey, expect.getSourceKey())
                .eq(com.panjia.received.domain.ReceivedDetail::getDetailStatus, "ACTIVE"));
            if (existing != null && existing > 0) {
                result.getSkippedReasons().put(expect.getId(), "已有实收事实，不能重复提交");
                continue;
            }
            try {
                com.panjia.received.domain.ReceivedContract contract =
                    getOrCreateManualContract(expect, period, effectiveOperator);
                com.panjia.received.domain.ReceivedDetail detail =
                    buildManualDetail(expect, contract.getId(), period, effectiveOperator);
                detailMapper.insert(detail);
                createdDetails.add(detail);
                touchedContractMap.put(contract.getId(), contract);
            } catch (Exception e) {
                result.getSkippedReasons().put(expect.getId(), "插入失败：" + e.getMessage());
            }
        }
        result.setCreatedDetailCount(createdDetails.size());
        if (createdDetails.isEmpty()) {
            return result;
        }
        // ③ 重算涉及合同的 ACTIVE 明细聚合（period_total_received / item_count）
        for (Long contractId : touchedContractMap.keySet()) {
            refreshContractAggregate(contractId);
        }
        // ④ 映射成内存事实走公共建单段（按订单号分组 + 活跃审批单合并 + startWorkflow）
        List<PerformanceFact> realFacts = mapDetailsToFacts(createdDetails, touchedContractMap, null);
        int applyCount = createApplyForRealFactsInternal(realFacts, period, effectiveOperator, null);
        result.setCreatedApplyCount(applyCount);
        log.info("[实收审批] 手工提交实收完成：period={}, 新建明细={}, 新建审批单={}, 跳过={}",
            period, result.getCreatedDetailCount(), applyCount, result.getSkippedReasons().size());
        return result;
    }

    /** 按 (order_no, period, MANUAL) 幂等查/建手工实收合同（uk_received_contract_anchor 兜底）。 */
    private com.panjia.received.domain.ReceivedContract getOrCreateManualContract(
            PerformanceFact expect, String period, Long operatorId) {
        com.panjia.received.domain.ReceivedContract existing = contractMapper.selectOne(
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedContract>()
                .eq(com.panjia.received.domain.ReceivedContract::getOrderNo, expect.getOrderNo())
                .eq(com.panjia.received.domain.ReceivedContract::getPeriod, period)
                .eq(com.panjia.received.domain.ReceivedContract::getSourceType, SOURCE_TYPE_MANUAL)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        com.panjia.received.domain.ReceivedContract contract = new com.panjia.received.domain.ReceivedContract();
        contract.setOrderNo(expect.getOrderNo());
        contract.setContractNo(expect.getContractNo());
        contract.setBizType(expect.getBizType());
        contract.setPeriod(period);
        contract.setBusinessDate(expect.getBusinessDate());
        contract.setBatchId(null);
        contract.setSourceType(SOURCE_TYPE_MANUAL);
        contract.setDeptId(expect.getDeptId());
        contract.setPropertyAddress(expect.getPropertyAddress());
        contract.setPeriodTotalReceived(BigDecimal.ZERO);
        contract.setItemCount(0);
        try {
            contractMapper.insert(contract);
        } catch (DuplicateKeyException e) {
            // 并发手工提交撞 uk_received_contract_anchor：重查取既有合同
            com.panjia.received.domain.ReceivedContract raced = contractMapper.selectOne(
                new LambdaQueryWrapper<com.panjia.received.domain.ReceivedContract>()
                    .eq(com.panjia.received.domain.ReceivedContract::getOrderNo, expect.getOrderNo())
                    .eq(com.panjia.received.domain.ReceivedContract::getPeriod, period)
                    .eq(com.panjia.received.domain.ReceivedContract::getSourceType, SOURCE_TYPE_MANUAL)
                    .last("LIMIT 1"));
            if (raced != null) {
                return raced;
            }
            throw e;
        }
        return contract;
    }

    /** 以 PERF_EXPECT 为镜像构造手工实收明细（sourceKey 沿用应收，rd 与导入 sourceKey 不同源不会撞锚点）。 */
    private com.panjia.received.domain.ReceivedDetail buildManualDetail(
            PerformanceFact expect, Long contractId, String period, Long operatorId) {
        com.panjia.received.domain.ReceivedDetail d = new com.panjia.received.domain.ReceivedDetail();
        d.setContractId(contractId);
        d.setEmployeeId(expect.getEmployeeId());
        d.setDeptId(expect.getDeptId());
        d.setEmployeeExternalCode(expect.getEmployeeExternalCode());
        d.setRoleType(expect.getRoleType());
        d.setRoleName(expect.getRoleName());
        d.setShareRatio(expect.getShareRatio() != null ? expect.getShareRatio() : BigDecimal.ONE);
        d.setPerformanceAmount(expect.getPerformanceAmount() != null ? expect.getPerformanceAmount() : BigDecimal.ZERO);
        d.setFeeItem(expect.getFeeItem());
        d.setPeriod(period);
        d.setEffectiveDate(expect.getEffectiveDate() != null ? expect.getEffectiveDate()
            : (expect.getBusinessDate() != null ? expect.getBusinessDate().toLocalDate() : java.time.LocalDate.now()));
        d.setSourceKey(expect.getSourceKey());
        d.setSourceBatchId(null);
        d.setNormalizedRecordId(null);
        d.setDetailStatus("ACTIVE");
        d.setOperatorId(operatorId);
        return d;
    }

    /** 重算合同级 ACTIVE 明细聚合（period_total_received / item_count）。 */
    private void refreshContractAggregate(Long contractId) {
        if (contractId == null) {
            return;
        }
        List<com.panjia.received.domain.ReceivedDetail> active = detailMapper.selectList(
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
                .eq(com.panjia.received.domain.ReceivedDetail::getContractId, contractId)
                .eq(com.panjia.received.domain.ReceivedDetail::getDetailStatus, "ACTIVE"));
        com.panjia.received.domain.ReceivedContract contract = contractMapper.selectById(contractId);
        if (contract == null) {
            return;
        }
        contract.setItemCount(active.size());
        contract.setPeriodTotalReceived(active.stream()
            .map(d -> d.getPerformanceAmount() == null ? BigDecimal.ZERO : d.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add));
        contractMapper.updateById(contract);
    }
}
