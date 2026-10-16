package com.panjia.received.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import com.panjia.performance.mapper.ReceivedAdjustMapper;
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
    private final com.panjia.received.mapper.ReceivedRealFactMapper realFactMapper;
    private final ApprovalPort approvalPort;
    private final ConfigService configService;
    private final TaskExecutor taskExecutor;
    /** 折算因子公共方法（取比例 / 金额乘算的唯一入口） */
    private final ConversionFactorPort conversionFactorPort;
    /** 部门子树解析（店长/总监数据权限范围） */
    private final DeptService deptService;
    /** 实收审批通过事件（结佣域按合同自动产生结佣记录，outbox 原子提交） */
    private final EventPort eventPort;
    /** 实收调整单 Mapper（pj_perf_received_adjust）：列表回填实收调整标记与调整前原值 */
    private final ReceivedAdjustMapper receivedAdjustMapper;

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
     * 业绩调整联动：合同「有新签、无实收」时，把联动建出的虚拟实收明细归入一张<b>审批通过</b>的实收审批单。
     * <p>
     * 场景：新签调整执行后实收侧无 ACTIVE 明细 → 联动按逐人 delta 建虚拟实收明细
     * （{@code source_type=MANUAL}、{@code received_apply_id} 为空）。这些明细若不入审批单，
     * 实收审批单列表查不到该笔实收，故此处直建 APPROVED 单（无流程实例）并绑定明细。
     * <p>幂等：同订单号当月已有活跃审批单（DRAFT/SUBMITTED/APPROVED）时跳过。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int autoCreateApprovedForAdjust(String period, String orderNo, String contractNo,
                                           Long operatorId, Long adjustId) {
        if (StringUtils.isBlank(period)
            || (StringUtils.isBlank(orderNo) && StringUtils.isBlank(contractNo))) {
            return 0;
        }
        // 兜底：联动执行场景一般有登录上下文，事件/定时场景可能没有
        if (operatorId == null) {
            operatorId = 1L;
        }
        // 该合同当月合同壳（联动建虚拟实收时已建/复用）
        LambdaQueryWrapper<com.panjia.received.domain.ReceivedContract> cw = new LambdaQueryWrapper<>();
        cw.eq(com.panjia.received.domain.ReceivedContract::getPeriod, period);
        if (StringUtils.isNotBlank(orderNo)) {
            cw.eq(com.panjia.received.domain.ReceivedContract::getOrderNo, orderNo);
        } else {
            cw.eq(com.panjia.received.domain.ReceivedContract::getContractNo, contractNo);
        }
        List<com.panjia.received.domain.ReceivedContract> contracts = contractMapper.selectList(cw);
        if (contracts.isEmpty()) {
            return 0;
        }
        Map<Long, com.panjia.received.domain.ReceivedContract> contractById = contracts.stream()
            .collect(Collectors.toMap(com.panjia.received.domain.ReceivedContract::getId, c -> c, (a, b) -> a));
        List<Long> contractIds = contracts.stream()
            .map(com.panjia.received.domain.ReceivedContract::getId).toList();
        // 未挂单的 ACTIVE 虚拟实收明细
        List<com.panjia.received.domain.ReceivedDetail> rows = detailMapper.selectList(
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
                .in(com.panjia.received.domain.ReceivedDetail::getContractId, contractIds)
                .eq(com.panjia.received.domain.ReceivedDetail::getPeriod, period)
                .eq(com.panjia.received.domain.ReceivedDetail::getDetailStatus, "ACTIVE")
                .isNull(com.panjia.received.domain.ReceivedDetail::getReceivedApplyId));
        if (rows.isEmpty()) {
            log.info("[实收审批] 业绩调整联动无可挂单实收明细，跳过：adjustId={}, period={}, contractNo={}",
                adjustId, period, contractNo);
            return 0;
        }
        // 按订单号分组建单：业绩调整联动场景下实收金额=新签调整差额，
        // 合同整体无实收、且调整净额为 0（如 A-100/B+100 的重分配）时也要建单，
        // 且差额为 0 的角色行（C=0）同样挂单，保证实收合同人员与新签一一对应（空订单号跳过）。
        Map<String, List<com.panjia.received.domain.ReceivedDetail>> rowsByOrder = new LinkedHashMap<>();
        Map<String, com.panjia.received.domain.ReceivedContract> contractByOrder = new HashMap<>();
        for (com.panjia.received.domain.ReceivedDetail d : rows) {
            com.panjia.received.domain.ReceivedContract c = contractById.get(d.getContractId());
            if (c == null || StringUtils.isBlank(c.getOrderNo())) {
                continue;
            }
            rowsByOrder.computeIfAbsent(c.getOrderNo(), k -> new ArrayList<>()).add(d);
            contractByOrder.putIfAbsent(c.getOrderNo(), c);
        }
        if (rowsByOrder.isEmpty()) {
            return 0;
        }
        // ===== 批量预加载：活跃审批单 + 应收合计（口径同历史直建） =====
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
            List<com.panjia.received.domain.ReceivedDetail> group = entry.getValue();
            // 幂等：同订单号当月已有活跃审批单时跳过（避免重复建单）
            if (activeApplies.containsKey(bizKey)) {
                log.info("[实收审批] 业绩调整联动跳过，订单当月已有审批单：applyId={}, orderNo={}",
                    activeApplies.get(bizKey).getId(), bizKey);
                continue;
            }
            BigDecimal realSum = group.stream()
                .map(r -> r.getPerformanceAmount() == null ? BigDecimal.ZERO : r.getPerformanceAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            Long uniqueDeptId = uniqueDetailDeptId(group, contractByOrder.get(bizKey));
            BigDecimal expectedAmount = expectedMap.getOrDefault(bizKey, BigDecimal.ZERO);
            try {
                ReceivedApply apply = newApplyFromContract(period, contractByOrder.get(bizKey), null, operatorId,
                    group.size(), realSum, expectedAmount, uniqueDeptId);
                // 审批终态直接 INSERT：APPROVED + 无流程实例（同历史批次直建语义）
                apply.setStatus(ReceivedApplyStatus.APPROVED);
                apply.setApproverId(operatorId);
                apply.setApproveTime(LocalDateTime.now());
                insertApply(apply);
                bindFacts(group.stream().map(com.panjia.received.domain.ReceivedDetail::getId).toList(),
                    apply.getId());
                created++;
                log.info("[实收审批] 业绩调整联动审批单直建：applyNo={}, orderNo={}, received={}, adjustId={}",
                    apply.getApplyNo(), apply.getOrderNo(), apply.getReceivedAmount(), adjustId);
            } catch (DuplicateKeyException e) {
                log.warn("[实收审批] 业绩调整联动直建撞唯一索引，跳过：period={}, orderNo={}", period, bizKey);
            }
        }
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
        // ===== 3. 批量查新签应收（ACTIVE PERF_EXPECT）→ 按匹配键归集到实收订单 =====
        // 匹配口径（2026-10-11 双侧闸门定稿）：合同号+订单号双键精确；双键未命中时，
        // 仅当新签事实与实收合同表两侧该合同的去重订单号都恰为 1（兼容新签 orderNo 误填成合同号）
        // 才退化按合同号归集，同合同多订单一律不退化（宁可不归集走人工，也不跨订单串单）。
        // 金额口径：当月优先——实收月有<b>非零</b>新签 → 只取当月合计；
        // 当月新签为 0/无 → 不参与当月计算，回退汇总历史（<实收月）合计
        ExpectMatchKeys matchKeys = ExpectMatchKeys.build(contractByOrder);
        matchKeys.retainGloballyDegradable(globallyDegradableContracts(matchKeys.degradeCandidates()));
        Map<String, BigDecimal> expectTotalByOrder = new HashMap<>();
        // 新签侧去重人员（当月优先），用于「新签-实收人数一致性」校验
        Map<String, Set<Long>> expectEmpsByOrder = new HashMap<>();
        if (!matchKeys.isEmpty()) {
            LambdaQueryWrapper<PerformanceFact> expectQ = new LambdaQueryWrapper<PerformanceFact>()
                .eq(PerformanceFact::getFactType, FACT_TYPE_EXPECT)
                .eq(PerformanceFact::getFactStatus, com.panjia.performance.domain.FactStatus.ACTIVE);
            if (!matchKeys.contractKeys.isEmpty() && !matchKeys.orderOnlyKeys.isEmpty()) {
                expectQ.and(w -> w.in(PerformanceFact::getContractNo, matchKeys.contractKeys)
                    .or().in(PerformanceFact::getOrderNo, matchKeys.orderOnlyKeys));
            } else if (!matchKeys.contractKeys.isEmpty()) {
                expectQ.in(PerformanceFact::getContractNo, matchKeys.contractKeys);
            } else {
                expectQ.in(PerformanceFact::getOrderNo, matchKeys.orderOnlyKeys);
            }
            Map<String, BigDecimal> currentMonthTotal = new HashMap<>();
            Map<String, BigDecimal> historyTotal = new HashMap<>();
            Map<String, Set<Long>> currentMonthEmps = new HashMap<>();
            Map<String, Set<Long>> historyEmps = new HashMap<>();
            for (PerformanceFact e : factMapper.selectList(expectQ)) {
                BigDecimal amt = nvlAmount(e.getPerformanceAmount());
                if (amt.signum() == 0) {
                    continue;
                }
                String targetOrderNo = matchKeys.resolveTarget(e.getOrderNo(), e.getContractNo());
                if (targetOrderNo == null) {
                    continue;
                }
                String fp = e.getPeriod();
                if (period.equals(fp)) {
                    currentMonthTotal.merge(targetOrderNo, amt, BigDecimal::add);
                    if (e.getEmployeeId() != null) {
                        currentMonthEmps.computeIfAbsent(targetOrderNo, k -> new LinkedHashSet<>())
                            .add(e.getEmployeeId());
                    }
                } else if (fp != null && fp.compareTo(period) < 0) {
                    historyTotal.merge(targetOrderNo, amt, BigDecimal::add);
                    if (e.getEmployeeId() != null) {
                        historyEmps.computeIfAbsent(targetOrderNo, k -> new LinkedHashSet<>())
                            .add(e.getEmployeeId());
                    }
                }
            }
            expectTotalByOrder.putAll(historyTotal);
            currentMonthTotal.forEach(expectTotalByOrder::put);
            // 人员口径与金额口径一致：当月有非零新签则只取当月人员，否则取历史人员
            expectEmpsByOrder.putAll(historyEmps);
            currentMonthEmps.forEach(expectEmpsByOrder::put);
        }
        // ===== 4. 活跃审批单批量预加载（判重维度同匹配口径） =====
        Map<String, ReceivedApply> activeApplies = loadActiveAppliesBatch(period, matchKeys);
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
                boolean hasNewSign = expectedAmount != null && expectedAmount.signum() != 0;
                if (!hasNewSign) {
                    // 无新签（新签0元行不进系统，expectedAmount=0 等价无新签）：DRAFT 不启动工作流、不 emit，等新签导入触发
                    // 注意：负数新签（红冲退单扣回）是有效业绩，signum()!=0 判定有新签，走下方正常审批流程
                    insertApply(apply);
                    bindFacts(factIds, apply.getId());
                    log.info("[实收审批] 贝壳实收无新签，DRAFT 待新签触发：applyNo={}, orderNo={}, received={}",
                        apply.getApplyNo(), orderNo, apply.getReceivedAmount());
                    created++;
                    continue;
                }
                // 合同维度判定：订单到账合计 vs 订单跨月新签合计；
                // 新增「新签-实收人数一致性」闸门：新签与实收的去重人员集合不一致（如新签 3 人、
                // 实收 2 人）时，即使实收金额 ≥ 新签金额也强制人工审批（卡审批中），不得自动通过。
                Set<Long> expectEmps = expectEmpsByOrder.getOrDefault(orderNo, Set.of());
                Set<Long> realEmps = rows.stream()
                    .map(com.panjia.received.domain.ReceivedDetail::getEmployeeId)
                    .filter(id -> id != null)
                    .collect(Collectors.toSet());
                boolean rosterMismatch = !expectEmps.isEmpty() && !expectEmps.equals(realEmps);
                if (rosterMismatch) {
                    log.info("[实收审批] 新签-实收人数不一致，转人工审批：orderNo={}, expectEmps={}, realEmps={}",
                        orderNo, expectEmps, realEmps);
                }
                boolean needManual = rosterMismatch || needManualReview(realSum, expectedAmount);
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
     * <p>负数（实收到账 &lt; 0 或 新签 &lt; 0，退单红冲扣回）直接人工，与「实收 &lt; 新签」同口径；
     * 否则到账 &lt; 订单跨月新签合计 → 人工（≥ 视为到账已覆盖，自动通过）。
     */
    private static boolean needManualReview(BigDecimal arrival, BigDecimal expectTotal) {
        BigDecimal amt = arrival == null ? BigDecimal.ZERO : arrival;
        BigDecimal expect = expectTotal == null ? BigDecimal.ZERO : expectTotal;
        // 负数（实收到账<0 或 新签<0，退单红冲扣回）直接人工审批，与「实收<新签」同口径
        if (amt.signum() < 0 || expect.signum() < 0) {
            return true;
        }
        // 到账>=0 且 新签>=0：到账<新签→人工，到账>=新签→自动通过
        return amt.compareTo(expect) < 0;
    }

    /** 发布实收审批通过事件（outbox 原子提交；contractNo 可为空，家装荐客等以 orderNo 为锚点）。 */
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
    public boolean resolveDraftAfterNewSign(String period, String contractNo, String orderNo, Long operatorId) {
        if (StringUtils.isBlank(period)
            || (StringUtils.isBlank(contractNo) && StringUtils.isBlank(orderNo))) {
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

        // 1. 查当月 DRAFT 实收审批单：主按订单号（订单号为唯一业务锚点，同合同多订单
        //    各自独立建单，按订单定位才不跨订单串单）；仅历史单据 order_no 为空时回退合同号
        LambdaQueryWrapper<ReceivedApply> draftQ = new LambdaQueryWrapper<ReceivedApply>()
            .eq(ReceivedApply::getPeriod, period)
            .eq(ReceivedApply::getStatus, ReceivedApplyStatus.DRAFT)
            .orderByAsc(ReceivedApply::getId);
        if (StringUtils.isNotBlank(orderNo)) {
            draftQ.eq(ReceivedApply::getOrderNo, orderNo);
        } else {
            draftQ.eq(ReceivedApply::getContractNo, contractNo);
        }
        List<ReceivedApply> drafts = applyMapper.selectList(draftQ);
        if (drafts.isEmpty()) {
            return false;
        }
        boolean anyApproved = false;
        for (ReceivedApply apply : drafts) {
            if (evaluateDraftAfterNewSign(apply, operatorId)) {
                anyApproved = true;
            }
        }
        return anyApproved;
    }

    /**
     * 评估单张 DRAFT 实收审批单（新签补触发）：按该单自身合同号+订单号双键（双侧闸门兼容脏数据）
     * 取当月优先新签合计；有新签且实收 ≥ 新签<b>且新签/实收人员一致</b> → 自动通过并 emit；
     * 实收 &lt; 新签 或 人员不一致 → 转人工审批；
     * 无对应新签（其他订单先到/双键不中且禁退化）→ 保持 DRAFT 等下次触发。
     *
     * @return 本单是否自动通过
     */
    private boolean evaluateDraftAfterNewSign(ReceivedApply apply, Long operatorId) {
        // 2. 当月优先查该单对应 PERF_EXPECT ACTIVE 合计（双键+双侧闸门，口径同 autoCreateForReceivedBatch）
        BigDecimal expectTotal = sumExpectByBizKey(apply.getPeriod(), apply.getOrderNo(), apply.getContractNo());
        if (expectTotal.signum() <= 0) {
            // 该单订单尚无新签（多订单中另一张单的新签先到，或双键不中且闸门禁止退化）：保持 DRAFT
            return false;
        }

        BigDecimal realSum = nvlAmount(apply.getReceivedAmount());
        apply.setExpectedAmount(expectTotal);
        // 新签-实收人数一致性闸门：人员集合不一致（如新签 3 人、实收 2 人）时强制人工审批，
        // 即使实收金额 ≥ 新签金额也不自动通过。
        Set<Long> expectEmps = expectEmpIdsByBizKey(apply.getPeriod(), apply.getOrderNo(), apply.getContractNo());
        Set<Long> realEmps = detailMapper.selectList(
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
                .eq(com.panjia.received.domain.ReceivedDetail::getReceivedApplyId, apply.getId())
                .eq(com.panjia.received.domain.ReceivedDetail::getDetailStatus, "ACTIVE"))
            .stream()
            .map(com.panjia.received.domain.ReceivedDetail::getEmployeeId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());
        boolean rosterMismatch = !expectEmps.isEmpty() && !expectEmps.equals(realEmps);
        if (realSum.compareTo(expectTotal) >= 0 && !rosterMismatch) {
            // 实收 ≥ 新签 且 人员一致 → 自动通过 + emit 实收通过事件
            apply.setStatus(ReceivedApplyStatus.APPROVED);
            apply.setApproverId(operatorId);
            apply.setApproveTime(LocalDateTime.now());
            applyMapper.updateById(apply);
            emitApprovedEvent(apply, operatorId);
            log.info("[实收审批] 新签触发自动通过：applyNo={}, orderNo={}, received={}, expect={}",
                apply.getApplyNo(), apply.getOrderNo(), realSum, expectTotal);
            return true;
        }
        // 实收 < 新签 或 人数不一致 → 启动人工审批工作流（与导入时"有新签但不足"路径一致）
        startWorkflow(apply, apply.getApplicantId(), Set.of());
        log.info("[实收审批] 新签触发人工审批（实收不足或人数不一致）：applyNo={}, orderNo={}, received={},"
            + " expect={}, rosterMismatch={}", apply.getApplyNo(), apply.getOrderNo(), realSum, expectTotal,
            rosterMismatch);
        return false;
    }

    /**
     * 新签-实收退化的<b>双侧全局闸门</b>（单合同，新签补触发/重算合计/详情用）：
     * 该合同 ACTIVE PERF_EXPECT 事实（跨全部期间）去重订单号恰 1 个，<b>且</b>
     * 实收合同表（全局，不限期间/批次）去重订单号恰 1 个，才允许双键未命中后退化按合同号匹配。
     * 基数≥2（同合同多订单，含分批到账）或 0（无事实）一律不退化，宁走人工也不串单。
     */
    private boolean canDegradeExpectToReceived(String contractNo) {
        if (StringUtils.isBlank(contractNo)) {
            return false;
        }
        long expectOrderCount = factMapper.countDistinctOrderNos(null, FACT_TYPE_EXPECT,
            List.of(com.panjia.performance.domain.FactStatus.ACTIVE.getCode()), contractNo);
        return expectOrderCount == 1L && contractMapper.countDistinctOrderNos(contractNo) == 1L;
    }

    /**
     * 双侧全局闸门批量版（贝壳建单入口用，每侧仅 1 条 group by SQL，避免 N+1）：
     * 入参为「本批仅挂一个实收订单」的候选合同，返回新签 ACTIVE 事实与实收合同表
     * 去重订单号都恰为 1 的合同集合。
     */
    private Set<String> globallyDegradableContracts(Set<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Set.of();
        }
        // 新签侧：一次查出 (合同号, 订单号) 去重对，内存按合同计数
        List<PerformanceFact> expectPairs = factMapper.selectList(
            new LambdaQueryWrapper<PerformanceFact>()
                .select(PerformanceFact::getContractNo, PerformanceFact::getOrderNo)
                .eq(PerformanceFact::getFactType, FACT_TYPE_EXPECT)
                .eq(PerformanceFact::getFactStatus, com.panjia.performance.domain.FactStatus.ACTIVE)
                .in(PerformanceFact::getContractNo, candidates)
                .isNotNull(PerformanceFact::getOrderNo)
                .ne(PerformanceFact::getOrderNo, "")
                .groupBy(PerformanceFact::getContractNo, PerformanceFact::getOrderNo));
        Map<String, Integer> expectCountByContract = new HashMap<>();
        for (PerformanceFact f : expectPairs) {
            expectCountByContract.merge(f.getContractNo(), 1, Integer::sum);
        }
        // 实收侧：实收合同表 (合同号, 订单号) 去重对（全局，不限期间/批次）
        List<com.panjia.received.domain.ReceivedContract> receivedPairs = contractMapper.selectList(
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedContract>()
                .select(com.panjia.received.domain.ReceivedContract::getContractNo,
                    com.panjia.received.domain.ReceivedContract::getOrderNo)
                .in(com.panjia.received.domain.ReceivedContract::getContractNo, candidates)
                .isNotNull(com.panjia.received.domain.ReceivedContract::getOrderNo)
                .ne(com.panjia.received.domain.ReceivedContract::getOrderNo, "")
                .groupBy(com.panjia.received.domain.ReceivedContract::getContractNo,
                    com.panjia.received.domain.ReceivedContract::getOrderNo));
        Map<String, Integer> receivedCountByContract = new HashMap<>();
        for (com.panjia.received.domain.ReceivedContract c : receivedPairs) {
            receivedCountByContract.merge(c.getContractNo(), 1, Integer::sum);
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (String cn : candidates) {
            if (expectCountByContract.getOrDefault(cn, 0) == 1
                && receivedCountByContract.getOrDefault(cn, 0) == 1) {
                allowed.add(cn);
            }
        }
        return allowed;
    }

    /**
     * 按「当月优先」口径计算该实收审批单对应的 ACTIVE PERF_EXPECT 新签金额合计。
     * 建单分流后的新签补触发、重算合计、详情顶部应收合计共用<b>唯一入口</b>。
     * <p>金额口径见 {@link #expectFactsByBizKey}。
     */
    private BigDecimal sumExpectByBizKey(String period, String orderNo, String contractNo) {
        return currentMonthPrioritySum(expectFactsByBizKey(period, orderNo, contractNo), period);
    }

    /**
     * 该实收审批单对应的新签（ACTIVE PERF_EXPECT）去重员工集合（当月优先口径，零金额行不计）。
     * <p>用于「新签-实收人数一致性」校验：双侧人数/人员不一致时必须转人工审批，不得自动通过。
     */
    private Set<Long> expectEmpIdsByBizKey(String period, String orderNo, String contractNo) {
        return currentMonthPriorityEmpIds(expectFactsByBizKey(period, orderNo, contractNo), period);
    }

    /**
     * 按「当月优先」口径取该 bizKey 对应的 ACTIVE PERF_EXPECT 新签事实（金额合计/人数校验共用唯一取数入口）。
     * <p>匹配口径（2026-10-11 双侧闸门定稿）：
     * <ul>
     *   <li>合同号+订单号齐全：双键精确；未命中时经 {@link #canDegradeExpectToReceived}
     *       双侧闸门（新签事实/实收合同全局订单都唯一）才退化按合同号汇总——兼容贝壳新签
     *       orderNo 误填成合同号；同合同多订单双键不中返回空（走人工，宁可不自动通过也不串单）；</li>
     *   <li>订单号为空（历史单据）：退化旧口径 contract_no/order_no 双键 OR；</li>
     *   <li>合同号为空：按订单号。</li>
     * </ul>
     */
    private List<PerformanceFact> expectFactsByBizKey(String period, String orderNo, String contractNo) {
        if (period == null) {
            return List.of();
        }
        boolean hasContract = StringUtils.isNotBlank(contractNo);
        boolean hasOrder = StringUtils.isNotBlank(orderNo);
        if (!hasContract && !hasOrder) {
            return List.of();
        }
        if (hasContract && hasOrder) {
            List<PerformanceFact> precise = factMapper.selectList(
                new LambdaQueryWrapper<PerformanceFact>()
                    .eq(PerformanceFact::getFactType, FACT_TYPE_EXPECT)
                    .eq(PerformanceFact::getFactStatus, com.panjia.performance.domain.FactStatus.ACTIVE)
                    .eq(PerformanceFact::getContractNo, contractNo)
                    .eq(PerformanceFact::getOrderNo, orderNo));
            if (!precise.isEmpty()) {
                return precise;
            }
            if (canDegradeExpectToReceived(contractNo)) {
                log.warn("[实收审批] 新签双键未命中且新签/实收两侧订单唯一，退化按合同号汇总：contractNo={}, orderNo={}",
                    contractNo, orderNo);
                return factMapper.selectList(
                    new LambdaQueryWrapper<PerformanceFact>()
                        .eq(PerformanceFact::getFactType, FACT_TYPE_EXPECT)
                        .eq(PerformanceFact::getFactStatus, com.panjia.performance.domain.FactStatus.ACTIVE)
                        .eq(PerformanceFact::getContractNo, contractNo));
            }
            log.warn("[实收审批] 新签双键未命中且两侧不满足订单唯一，不退化合同号匹配：contractNo={}, orderNo={}",
                contractNo, orderNo);
            return List.of();
        }
        // 历史单据/缺键：合同号在时走旧 OR 宽松口径，否则按订单号
        LambdaQueryWrapper<PerformanceFact> q = new LambdaQueryWrapper<PerformanceFact>()
            .eq(PerformanceFact::getFactType, FACT_TYPE_EXPECT)
            .eq(PerformanceFact::getFactStatus, com.panjia.performance.domain.FactStatus.ACTIVE);
        if (hasContract) {
            q.and(w -> w.eq(PerformanceFact::getContractNo, contractNo)
                .or().eq(PerformanceFact::getOrderNo, contractNo));
        } else {
            q.eq(PerformanceFact::getOrderNo, orderNo);
        }
        return factMapper.selectList(q);
    }

    /** 当月优先合计：当月有非零事实只取当月，否则取历史（&lt; period）合计，零金额行不计。 */
    private BigDecimal currentMonthPrioritySum(List<PerformanceFact> facts, String period) {
        BigDecimal currentSum = BigDecimal.ZERO;
        boolean hasCurrent = false;
        BigDecimal historySum = BigDecimal.ZERO;
        for (PerformanceFact e : facts) {
            BigDecimal amt = nvlAmount(e.getPerformanceAmount());
            if (amt.signum() == 0) {
                continue;
            }
            String fp = e.getPeriod();
            if (period.equals(fp)) {
                hasCurrent = true;
                currentSum = currentSum.add(amt);
            } else if (fp != null && fp.compareTo(period) < 0) {
                historySum = historySum.add(amt);
            }
        }
        return hasCurrent ? currentSum : historySum;
    }

    /** 当月优先员工集合：当月有非零事实只取当月员工，否则取历史（&lt; period）员工，零金额行不计。 */
    private Set<Long> currentMonthPriorityEmpIds(List<PerformanceFact> facts, String period) {
        Set<Long> current = new LinkedHashSet<>();
        Set<Long> history = new LinkedHashSet<>();
        for (PerformanceFact e : facts) {
            if (nvlAmount(e.getPerformanceAmount()).signum() == 0 || e.getEmployeeId() == null) {
                continue;
            }
            String fp = e.getPeriod();
            if (period.equals(fp)) {
                current.add(e.getEmployeeId());
            } else if (fp != null && fp.compareTo(period) < 0) {
                history.add(e.getEmployeeId());
            }
        }
        return current.isEmpty() ? history : current;
    }

    /**
     * 计算「转人工审批」原因（仅 SUBMITTED 审批中单据返回非空）。
     * <p>与自动通过判定点同一口径：先查新签-实收人数一致性（不一致优先提示），再查实收&lt;新签。
     * 列表/详情调用，让申请人与审批人知道为什么卡在审批中。</p>
     *
     * @param apply     审批单（需含 period/orderNo/contractNo/receivedAmount/expectedAmount）
     * @param realEmps  实收侧去重员工集合（调用方批量查好后传入，避免循环内重复查询）
     */
    private String manualReviewReasonOf(ReceivedApply apply, Set<Long> realEmps) {
        if (apply == null || apply.getStatus() != ReceivedApplyStatus.SUBMITTED) {
            return null;
        }
        Set<Long> expectEmps = expectEmpIdsByBizKey(apply.getPeriod(), apply.getOrderNo(), apply.getContractNo());
        if (!expectEmps.isEmpty() && !expectEmps.equals(realEmps)) {
            return String.format("新签与实收人数不一致（新签 %d 人/实收 %d 人），需人工核对人员",
                expectEmps.size(), realEmps.size());
        }
        BigDecimal realSum = nvlAmount(apply.getReceivedAmount());
        BigDecimal expectSum = apply.getExpectedAmount() == null ? BigDecimal.ZERO : apply.getExpectedAmount();
        if (expectSum.signum() > 0 && realSum.compareTo(expectSum) < 0) {
            return "实收金额小于新签金额，需人工核对";
        }
        return null;
    }

    /**
     * 新签-实收匹配键（2026-10-11 双侧闸门定稿）：<b>合同号+订单号双键同时满足精确归集</b>；
     * 双键未命中时，仅当该合同在<b>本批实收订单、新签 ACTIVE 事实、实收合同表全局</b>三处
     * 订单维度都唯一才退化按合同号归集（兼容贝壳新签 orderNo 误填成合同号的脏数据）；
     * 合同号缺失再回退订单号。
     * <p>背景：同一合同号可能挂多个订单号，旧版「合同号优先 + putIfAbsent」会把该合同下
     * 所有订单的应收事实归集到第一个实收订单，导致第二个订单 expectedAmount=0、自动分流判错。
     * 建单批量查新签 / 活跃审批单判重共用此口径；新签补触发/重算合计走
     * {@link #sumExpectByBizKey} 单合同双侧闸门，口径一致。
     */
    private static final class ExpectMatchKeys {
        /** 有合同号的实收：宽捞新签事实用（合同号集合），归集仍以双键为准 */
        final Set<String> contractKeys = new java.util.LinkedHashSet<>();
        /** 无合同号的实收：回退按新签/审批单 order_no 匹配（订单号集合） */
        final Set<String> orderOnlyKeys = new java.util.LinkedHashSet<>();
        /** 精确双键：合同号 + 订单号 → 实收分组订单号（命中后归集到 factsByOrder 分组键） */
        final Map<String, String> pairToOrderNo = new HashMap<>();
        /**
         * 允许合同号退化的映射：合同号 → 实收分组订单号。
         * build 时先放入「本批唯一」候选，再由 {@link #retainGloballyDegradable}
         * 按双侧全局闸门（新签事实/实收合同表去重订单号都恰为 1）过滤。
         */
        final Map<String, String> contractUnique = new HashMap<>();

        private static String pairKey(String contractNo, String orderNo) {
            return contractNo + '' + orderNo;
        }

        static ExpectMatchKeys build(
            Map<String, com.panjia.received.domain.ReceivedContract> contractByOrder) {
            ExpectMatchKeys m = new ExpectMatchKeys();
            // 合同号 → 本批实收订单号集合（本批唯一只是退化候选，还须过双侧全局闸门）
            Map<String, Set<String>> ordersByContract = new HashMap<>();
            contractByOrder.forEach((orderNo, c) -> {
                String cn = c == null ? null : c.getContractNo();
                if (StringUtils.isNotBlank(cn)) {
                    m.contractKeys.add(cn);
                    if (StringUtils.isNotBlank(c.getOrderNo())) {
                        m.pairToOrderNo.put(pairKey(cn, c.getOrderNo()), orderNo);
                    }
                    ordersByContract.computeIfAbsent(cn, k -> new LinkedHashSet<>()).add(orderNo);
                } else {
                    m.orderOnlyKeys.add(orderNo);
                }
            });
            ordersByContract.forEach((cn, orders) -> {
                if (orders.size() == 1) {
                    m.contractUnique.put(cn, orders.iterator().next());
                }
            });
            return m;
        }

        /** 本批唯一的候选退化合同（调用方据此批量查双侧全局基数，避免 N+1）。 */
        Set<String> degradeCandidates() {
            return new LinkedHashSet<>(contractUnique.keySet());
        }

        /**
         * 应用双侧全局闸门：仅保留新签 ACTIVE 事实与实收合同表全局去重订单号都恰为 1 的合同。
         *
         * @param degradableContracts 两侧基数都为 1 的合同号集合
         */
        void retainGloballyDegradable(Set<String> degradableContracts) {
            contractUnique.keySet().retainAll(degradableContracts);
        }

        /**
         * 按新签行/审批单的合同号+订单号解析归集目标实收 orderNo；不匹配返回 null。
         * 顺序：双键精确 → 合同唯一订单退化 → 无合同号按订单号；同合同多订单且双键不中
         * （新签订单号与实收对不上）返回 null，宁可不归集也不串到其他订单。
         */
        String resolveTarget(String orderNo, String contractNo) {
            if (StringUtils.isNotBlank(contractNo)) {
                if (StringUtils.isNotBlank(orderNo)) {
                    String exact = pairToOrderNo.get(pairKey(contractNo, orderNo));
                    if (exact != null) {
                        return exact;
                    }
                }
                String unique = contractUnique.get(contractNo);
                if (unique != null) {
                    return unique;
                }
                return null;
            }
            if (orderNo != null && orderOnlyKeys.contains(orderNo)) {
                return orderNo;
            }
            return null;
        }

        boolean isEmpty() {
            return contractKeys.isEmpty() && orderOnlyKeys.isEmpty();
        }
    }

    /** IN 键分片大小：键集随业务量可达数千上万，整包 IN 会撑爆 SQL 文本（parse 开销/内存），分片查询后内存合并 */
    private static final int IN_KEYS_CHUNK = 500;

    /** 键集合分片（去重保序）。 */
    private static List<List<String>> chunkKeys(Collection<String> keys) {
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(keys));
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < distinct.size(); i += IN_KEYS_CHUNK) {
            chunks.add(distinct.subList(i, Math.min(i + IN_KEYS_CHUNK, distinct.size())));
        }
        return chunks;
    }

    /**
     * 按匹配键（订单号优先、合同号兜底）分片批量取当月活跃审批单（DRAFT/SUBMITTED/APPROVED），
     * 归集到实收 orderNo 分组键，同一键取最新一张。贝壳自动建单判重用。
     */
    private Map<String, ReceivedApply> loadActiveAppliesBatch(String period, ExpectMatchKeys keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        // IN 键分片：实收键集随批次可达数千，整包 IN 会撑爆 SQL 文本。
        // 合同号/订单号两列各自分片查询（(c∈A OR o∈B) = A 列查 ∪ B 列查），按 ID 去重合并
        Map<Long, ReceivedApply> mergedById = new LinkedHashMap<>();
        for (List<String> chunk : chunkKeys(keys.contractKeys)) {
            for (ReceivedApply apply : applyMapper.selectList(new LambdaQueryWrapper<ReceivedApply>()
                .eq(ReceivedApply::getPeriod, period)
                .in(ReceivedApply::getContractNo, chunk)
                .in(ReceivedApply::getStatus,
                    ReceivedApplyStatus.DRAFT, ReceivedApplyStatus.SUBMITTED, ReceivedApplyStatus.APPROVED))) {
                mergedById.putIfAbsent(apply.getId(), apply);
            }
        }
        for (List<String> chunk : chunkKeys(keys.orderOnlyKeys)) {
            for (ReceivedApply apply : applyMapper.selectList(new LambdaQueryWrapper<ReceivedApply>()
                .eq(ReceivedApply::getPeriod, period)
                .in(ReceivedApply::getOrderNo, chunk)
                .in(ReceivedApply::getStatus,
                    ReceivedApplyStatus.DRAFT, ReceivedApplyStatus.SUBMITTED, ReceivedApplyStatus.APPROVED))) {
                mergedById.putIfAbsent(apply.getId(), apply);
            }
        }
        // 同键取最新一张：按 ID 全局降序（putIfAbsent 依赖全局有序）
        List<ReceivedApply> applies = new ArrayList<>(mergedById.values());
        applies.sort((a, b) -> Long.compare(b.getId(), a.getId()));
        Map<String, ReceivedApply> result = new HashMap<>();
        for (ReceivedApply apply : applies) {
            String target = keys.resolveTarget(apply.getOrderNo(), apply.getContractNo());
            if (target != null) {
                result.putIfAbsent(target, apply);
            }
        }
        return result;
    }

    /**
     * 分片 IN 查询批量取指定订单号当月活跃审批单（DRAFT/SUBMITTED/APPROVED），同一订单号取最新一张。
     * 手工提交 / 历史工资路径用（按 orderNo 口径）。
     */
    private Map<String, ReceivedApply> loadActiveAppliesBatch(String period, Collection<String> orderNos) {
        if (orderNos.isEmpty()) {
            return Map.of();
        }
        List<ReceivedApply> applies = new ArrayList<>();
        for (List<String> chunk : chunkKeys(orderNos)) {
            applies.addAll(applyMapper.selectList(new LambdaQueryWrapper<ReceivedApply>()
                .eq(ReceivedApply::getPeriod, period)
                .in(ReceivedApply::getOrderNo, chunk)
                .in(ReceivedApply::getStatus,
                    ReceivedApplyStatus.DRAFT, ReceivedApplyStatus.SUBMITTED, ReceivedApplyStatus.APPROVED)));
        }
        // 跨分片合并后按 ID 全局降序（下方 putIfAbsent 取最新依赖全局有序）
        applies.sort((a, b) -> Long.compare(b.getId(), a.getId()));
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
     * 分片 IN 查询批量取指定合同当月 SUBMITTED 实收审批单（批量审批预检用）。
     * <p>按输入字符串（合同号或订单号）双键建映射，同一合同取 ID 最大（最新）一张。
     */
    private Map<String, ReceivedApply> loadSubmittedAppliesBatch(String period, Collection<String> contractNos) {
        if (contractNos.isEmpty()) {
            return Map.of();
        }
        List<ReceivedApply> applies = new ArrayList<>();
        for (List<String> chunk : chunkKeys(contractNos)) {
            applies.addAll(applyMapper.selectList(new LambdaQueryWrapper<ReceivedApply>()
                .eq(ReceivedApply::getPeriod, period)
                .and(w -> w.in(ReceivedApply::getContractNo, chunk)
                    .or().in(ReceivedApply::getOrderNo, chunk))
                .eq(ReceivedApply::getStatus, ReceivedApplyStatus.SUBMITTED)));
        }
        // 跨分片合并后按 ID 全局降序（下方 putIfAbsent 取最新依赖全局有序）
        applies.sort((a, b) -> Long.compare(b.getId(), a.getId()));
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
        // 分页查询：三个业绩列表统一排序（签约/认购时间倒序 → 订单号(空取合同号)次序 → id 倒序兜底），
        // 不再按建单时间排（批量导入时建单时间集中且与业务发生顺序无关）
        QueryWrapper<ReceivedApply> wrapper = buildListCondition(query, effectiveDeptId);
        wrapper.orderByDesc("business_date")
            .last(", COALESCE(order_no, contract_no), id DESC");
        Page<ReceivedApply> page = applyMapper.selectPage(pageQuery.build(), wrapper);
        List<ReceivedApply> records = page.getRecords();
        fillContractMetrics(records);
        fillManualReviewReason(records);

        // 汇总与列表行口径：与列表完全同条件（不含排序）一次聚合，随 summary 返回；
        // 未按员工筛选时为单据级（合同/订单）合计，按员工筛选时改为该员工自己的明细合计与应收份额
        // （单据级金额含同合同其他角色人金额，员工筛选时必须只统计该员工自己的部分）
        QueryWrapper<ReceivedApply> sumWrapper = buildListCondition(query, effectiveDeptId);
        BigDecimal totalReceivedAmount = BigDecimal.ZERO;
        BigDecimal totalExpectedAmount = BigDecimal.ZERO;
        BigDecimal totalExpectedConvertedAmount = BigDecimal.ZERO;
        List<Map<String, Object>> empAmountRows = null;
        if (query.getEmployeeId() != null) {
            sumWrapper.select("id");
            List<Object> applyIds = applyMapper.selectObjs(sumWrapper);
            if (applyIds != null && !applyIds.isEmpty()) {
                // 汇总该员工在满足条件单据下的 ACTIVE 明细合计（员工口径），不能用单据级 received_amount 合计
                totalReceivedAmount = detailMapper.sumAmountByApplyIdsAndEmployee(applyIds, query.getEmployeeId());
                // 每单该员工实收/应收合计：列表行展示用；应收按业务类型分组折算后累加得汇总
                empAmountRows = detailMapper.selectEmployeeAmountsByApplyIds(applyIds, query.getEmployeeId());
                Map<String, BigDecimal> expectedByBiz = new HashMap<>();
                for (Map<String, Object> r : empAmountRows) {
                    Object biz = r.get("bizType");
                    Object amt = r.get("expectedAmount");
                    BigDecimal a = amt == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(amt));
                    expectedByBiz.merge(biz == null ? "" : String.valueOf(biz), a, BigDecimal::add);
                }
                Map<String, BigDecimal> factorMap = conversionFactorPort.factorsOf(expectedByBiz.keySet());
                for (Map.Entry<String, BigDecimal> e : expectedByBiz.entrySet()) {
                    BigDecimal amt = e.getValue();
                    String biz = e.getKey().isEmpty() ? null : e.getKey();
                    totalExpectedAmount = totalExpectedAmount.add(amt);
                    totalExpectedConvertedAmount = totalExpectedConvertedAmount.add(
                        conversionFactorPort.convert(amt, conversionFactorPort.factorOf(factorMap, biz)));
                }
            }
        } else {
            sumWrapper.select("COALESCE(SUM(received_amount), 0) AS total_received_amount");
            List<Map<String, Object>> sumRows = applyMapper.selectMaps(sumWrapper);
            if (sumRows != null && !sumRows.isEmpty() && sumRows.get(0) != null) {
                Object v = sumRows.get(0).get("total_received_amount");
                if (v == null) {
                    v = sumRows.get(0).get("totalReceivedAmount");
                }
                if (v != null) {
                    totalReceivedAmount = new BigDecimal(String.valueOf(v));
                }
            }
            // 折算后合计：新签业绩（应收）按业务类型分组金额 × 类型系数累加，汇总条与实收合计并排展示
            // （实收不做折算；应收口径与列表行 / 详情「应收合计·折算后」一致）
            QueryWrapper<ReceivedApply> bizWrapper = buildListCondition(query, effectiveDeptId);
            bizWrapper.select("biz_type", "COALESCE(SUM(expected_amount), 0) AS total_expected_amount");
            bizWrapper.groupBy("biz_type");
            List<Map<String, Object>> bizRows = applyMapper.selectMaps(bizWrapper);
            if (bizRows != null && !bizRows.isEmpty()) {
                Map<String, BigDecimal> factorMap = conversionFactorPort.factorsOf(bizRows.stream()
                    .map(m -> m.get("bizType") != null ? m.get("bizType") : m.get("biz_type"))
                    .filter(java.util.Objects::nonNull)
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.toSet()));
                for (Map<String, Object> m : bizRows) {
                    Object amtObj = m.get("totalExpectedAmount") != null ? m.get("totalExpectedAmount") : m.get("total_expected_amount");
                    BigDecimal amt = amtObj == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(amtObj));
                    Object bizObj = m.get("bizType") != null ? m.get("bizType") : m.get("biz_type");
                    String biz = bizObj == null ? null : String.valueOf(bizObj);
                    totalExpectedAmount = totalExpectedAmount.add(amt);
                    totalExpectedConvertedAmount = totalExpectedConvertedAmount.add(
                        conversionFactorPort.convert(amt, conversionFactorPort.factorOf(factorMap, biz)));
                }
            }
        }

        // 按员工筛选：列表行金额改为该员工口径（实收明细合计 + 应收份额），
        // 并清除合同级「原值 → 调整后」箭头（调整作用于单据级，员工口径下无意义）
        if (empAmountRows != null) {
            Map<String, Map<String, Object>> empByApply = new HashMap<>();
            for (Map<String, Object> r : empAmountRows) {
                empByApply.put(String.valueOf(r.get("applyId")), r);
            }
            Map<String, BigDecimal> rowFactorMap = conversionFactorPort.factorsOf(records.stream()
                .map(ReceivedApply::getBizType).filter(StringUtils::isNotBlank)
                .collect(java.util.stream.Collectors.toSet()));
            for (ReceivedApply a : records) {
                Map<String, Object> r = empByApply.get(String.valueOf(a.getId()));
                BigDecimal empReceived = r == null || r.get("receivedAmount") == null
                    ? BigDecimal.ZERO : new BigDecimal(String.valueOf(r.get("receivedAmount")));
                BigDecimal empExpected = r == null || r.get("expectedAmount") == null
                    ? BigDecimal.ZERO : new BigDecimal(String.valueOf(r.get("expectedAmount")));
                a.setReceivedAmount(empReceived);
                a.setExpectedAmount(empExpected);
                a.setExpectedConvertedAmount(conversionFactorPort.convert(empExpected,
                    conversionFactorPort.factorOf(rowFactorMap, a.getBizType())));
                a.setReceivedAdjusted(false);
                a.setOriginalReceivedAmount(null);
                a.setExpectedAdjusted(false);
                a.setOriginalExpectedAmount(null);
                a.setOriginalExpectedConvertedAmount(null);
            }
        }

        PageResult<ReceivedApply> result = PageResult.build(records, page.getTotal());
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalReceivedAmount", totalReceivedAmount);
        summary.put("totalExpectedAmount", totalExpectedAmount);
        summary.put("totalExpectedConvertedAmount", totalExpectedConvertedAmount);

        result.setSummary(summary);
        return result;
    }

    /**
     * 实收审批单列表 WHERE 条件（分页查询与全量汇总共用，保证口径一致）。
     * <p>
     * 默认排除已作废（CANCELLED），与业绩明细只查 ACTIVE 一致；
     * 前端显式传 status 时按指定状态查询（含 CANCELLED）。
     * 审批节点数据隔离：审批中单据只允许本人角色对应节点可见（前端不再传节点参数，防绕过由服务端强制）；
     * 门店/组别筛选通过实收明细关联员工归属部门过滤（合同下人员可能跨部门，不能用审批单的单一 dept_id）。
     * 拆表后 PERF_REAL 已迁出 pj_perf_fact → pj_received_detail；导入时 employee_id 暂留空、
     * 只存 employee_external_code，故须按外部工号关联 pj_people_employee 取 dept_id
     * （兼容后续 employee_id 回填：优先 ID 直连，为空时按工号匹配）。
     */
    private QueryWrapper<ReceivedApply> buildListCondition(ReceivedApplyBo query, Long effectiveDeptId) {
        QueryWrapper<ReceivedApply> wrapper = new QueryWrapper<>();
        wrapper.eq(StringUtils.isNotBlank(query.getPeriod()), "period", query.getPeriod())
            .eq(query.getBatchId() != null, "batch_id", query.getBatchId())
            // 业务类型已落库（biz_type，见 V140009）：筛选下推到 SQL
            .eq(StringUtils.isNotBlank(query.getBizType()), "biz_type", query.getBizType())
            .ne(StringUtils.isBlank(query.getStatus()), "status", ReceivedApplyStatus.CANCELLED)
            .eq(StringUtils.isNotBlank(query.getStatus()), "status", ReceivedApplyStatus.fromCode(query.getStatus()))
            .and(StringUtils.isNotBlank(query.getKeyword()), w -> w
                .like("contract_no", query.getKeyword())
                .or().like("order_no", query.getKeyword())
                .or().like("property_address", query.getKeyword()));
        // 审批节点数据隔离：审批中单据只允许本人角色对应节点可见
        applyApprovalNodeScope(wrapper);
        if (effectiveDeptId != null) {
            wrapper.apply(
                "EXISTS (SELECT 1 FROM pj_received_detail rd"
                    + " JOIN pj_people_employee e ON (e.employee_id = rd.employee_id"
                    + "   OR (NOT EXISTS (SELECT 1 FROM pj_people_employee x WHERE x.employee_id = rd.employee_id)"
                    + "       AND e.employee_code = rd.employee_external_code))"
                    + " WHERE rd.received_apply_id = pj_perf_received_apply.id"
                    + " AND rd.detail_status = 'ACTIVE'"
                    + " AND (e.dept_id = {0}"
                    + " OR e.dept_id IN (SELECT sd.dept_id FROM sys_dept sd"
                    + " WHERE sd.ancestors LIKE CONCAT('%', {0}, '%'))))",
                effectiveDeptId);
        }
        // 员工筛选：通过实收明细关联员工（口径同上）
        if (query.getEmployeeId() != null) {
            wrapper.apply(
                "EXISTS (SELECT 1 FROM pj_received_detail rd"
                    + " JOIN pj_people_employee e ON (e.employee_id = rd.employee_id"
                    + "   OR (NOT EXISTS (SELECT 1 FROM pj_people_employee x WHERE x.employee_id = rd.employee_id)"
                    + "       AND e.employee_code = rd.employee_external_code))"
                    + " WHERE rd.received_apply_id = pj_perf_received_apply.id"
                    + " AND rd.detail_status = 'ACTIVE'"
                    + " AND e.employee_id = {0})",
                query.getEmployeeId());
        }
        return wrapper;
    }

    @Override
    public List<String> listPeriods() {
        return applyMapper.selectDistinctPeriods();
    }

    /**
     * 回填实收明细列表的补充字段（涉及人数、应收合计）。
     * <p>
     * 业务类型已落库（{@code biz_type}，见 V140009），本方法只在旧数据 bizType 为空时
     * 按 (period, contractNo, orderNo) 从 ACTIVE 事实回退补齐；涉及人数与应收合计仍需实时聚合，
     * 口径与详情弹窗「每人实收明细」一致；按期间分组批量查询，避免 N+1。
     * 应收合计含已生效调整（新签业绩显示调整后金额），与快照不一致时置「已调整」标记。
     * 同合同号挂多订单时按合同号+订单号双键聚合；合同号缺失（一手房/家装荐客等）
     * 直接按订单号聚合（订单号为唯一业务锚点）。
     * 期间/合同号/订单号全缺失的行保持 null，前端显示占位符。
     */
    /**
     * 列表页批量填充「转人工审批原因」（仅 SUBMITTED 审批中行）。
     * <p>实收侧人员一次批量查询（入口批量取数，避免行内循环查库）；
     * 新签侧人员按单据 bizKey 逐单取（仅审批中行，受分页大小约束）。</p>
     */
    private void fillManualReviewReason(List<ReceivedApply> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<ReceivedApply> submitted = records.stream()
            .filter(a -> a.getStatus() == ReceivedApplyStatus.SUBMITTED)
            .toList();
        if (submitted.isEmpty()) {
            return;
        }
        List<Long> applyIds = submitted.stream().map(ReceivedApply::getId).filter(java.util.Objects::nonNull).toList();
        // 实收侧人员：一条 SQL 批量取本页所有审批中单的 ACTIVE 明细，再按单分组
        Map<Long, Set<Long>> realEmpsByApply = new HashMap<>();
        if (!applyIds.isEmpty()) {
            for (com.panjia.received.domain.ReceivedDetail d : detailMapper.selectList(
                new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
                    .in(com.panjia.received.domain.ReceivedDetail::getReceivedApplyId, applyIds)
                    .eq(com.panjia.received.domain.ReceivedDetail::getDetailStatus, "ACTIVE"))) {
                if (d.getReceivedApplyId() != null && d.getEmployeeId() != null) {
                    realEmpsByApply.computeIfAbsent(d.getReceivedApplyId(), k -> new LinkedHashSet<>())
                        .add(d.getEmployeeId());
                }
            }
        }
        for (ReceivedApply apply : submitted) {
            apply.setManualReviewReason(manualReviewReasonOf(apply,
                realEmpsByApply.getOrDefault(apply.getId(), Set.of())));
        }
    }

    private void fillContractMetrics(List<ReceivedApply> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        // 本页需要退化判定的合同（合同号+订单号齐全）：一次批量双侧闸门（2 条 group by SQL），
        // 口径与建单/详情一致——新签 order_no 误填成合同号且两侧订单都唯一时，应收按合同号配对
        Set<String> preciseContracts = records.stream()
            .filter(a -> StringUtils.isNotBlank(a.getPeriod()) && StringUtils.isNotBlank(a.getContractNo())
                && StringUtils.isNotBlank(a.getOrderNo()))
            .map(ReceivedApply::getContractNo)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> degradableContracts = globallyDegradableContracts(preciseContracts);
        // period → 键对集合（ck=合同号可空、ok=订单号可空、dg 退化标志），同键对去重避免重复 VALUES 行。
        // 键对直接取单据自身 (contractNo, orderNo)，不做「订单号塞进合同号参数」的 hack；
        // metrics SQL 按四分支匹配：dg→按合同号退化 / 双键精确 / ck=''→按订单号 / ok=''→历史 OR 宽松口径
        Map<String, Map<String, Map<String, String>>> keysByPeriod = new HashMap<>();
        for (ReceivedApply apply : records) {
            if (StringUtils.isBlank(apply.getPeriod())) {
                continue;
            }
            String contractNo = StringUtils.isBlank(apply.getContractNo()) ? "" : apply.getContractNo();
            String orderNo = StringUtils.isBlank(apply.getOrderNo()) ? "" : apply.getOrderNo();
            if (contractNo.isEmpty() && orderNo.isEmpty()) {
                continue;
            }
            Map<String, String> keyMap = new HashMap<>(3);
            keyMap.put("contractNo", contractNo);
            keyMap.put("orderNo", orderNo);
            keyMap.put("degrade", !contractNo.isEmpty() && !orderNo.isEmpty()
                && degradableContracts.contains(contractNo) ? "true" : "false");
            Map<String, Map<String, String>> byPair =
                keysByPeriod.computeIfAbsent(apply.getPeriod(), k -> new LinkedHashMap<>());
            byPair.computeIfAbsent(metricsPairKey(contractNo, orderNo), k -> keyMap);
        }
        if (keysByPeriod.isEmpty()) {
            return;
        }
        Map<String, ReceivedContractMetricsVo> metrics = new HashMap<>();
        for (Map.Entry<String, Map<String, Map<String, String>>> entry : keysByPeriod.entrySet()) {
            List<ReceivedContractMetricsVo> rows =
                factMapper.selectReceivedContractMetrics(entry.getKey(), entry.getValue().values());
            for (ReceivedContractMetricsVo row : rows) {
                metrics.put(metricsKey(entry.getKey(), row.getContractNo(),
                    StringUtils.isBlank(row.getOrderNo()) ? "" : row.getOrderNo()), row);
            }
        }
        // 一次性批量取本页全部 bizType 的折算因子（避免循环内逐条 factorOf(String) 触发全表扫描 pj_payroll_conversion_rule）
        Set<String> bizTypes = metrics.values().stream()
            .map(ReceivedContractMetricsVo::getBizType)
            .filter(StringUtils::isNotBlank)
            .collect(java.util.stream.Collectors.toSet());
        Map<String, BigDecimal> factorMap = conversionFactorPort.factorsOf(bizTypes);

        // 实收调整：按期间批量查已执行单（取调整前原值）与审批中单（取目标金额+标记）
        // 业务键集合（合同号+订单号混合），按期间分组查询避免跨期串单
        Map<String, Set<String>> keysByPeriodForAdjust = new HashMap<>();
        for (ReceivedApply apply : records) {
            if (StringUtils.isBlank(apply.getPeriod())) {
                continue;
            }
            Set<String> keys = keysByPeriodForAdjust.computeIfAbsent(apply.getPeriod(), k -> new LinkedHashSet<>());
            if (StringUtils.isNotBlank(apply.getContractNo())) {
                keys.add(apply.getContractNo());
            }
            if (StringUtils.isNotBlank(apply.getOrderNo())) {
                keys.add(apply.getOrderNo());
            }
        }
        // period → bizKey → 实收链原值（当前 ACTIVE rd 的 sourceKey 链最早金额合计，含 REVERSED 历史；
        // 多次调整取最初原值，与每人明细 originalAmount 求和同口径）
        Map<String, Map<String, BigDecimal>> originalByPeriod = new HashMap<>();
        // period → bizKey → 是否「已调整」（链上存在 REVERSED 或 MANUAL-RADJ 新人行；不受金额是否变影响）
        Map<String, Map<String, Boolean>> adjustedByPeriod = new HashMap<>();
        // period → bizKey → 新签（应收）是否「已调整」（pexp 链存在 REVERSED 或 MANUAL-ADJ/MANUAL-CADJ 新增角色人链）
        Map<String, Map<String, Boolean>> expectedAdjustedByPeriod = new HashMap<>();
        // period → bizKey → 审批中调整单（targetAmount）
        Map<String, Map<String, Map<String, Object>>> pendingByPeriod = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : keysByPeriodForAdjust.entrySet()) {
            String period = entry.getKey();
            Set<String> keys = entry.getValue();
            if (keys.isEmpty()) {
                continue;
            }
            Map<String, BigDecimal> origMap = new HashMap<>();
            Map<String, Boolean> adjMap = new HashMap<>();
            for (Map<String, Object> row : realFactMapper.selectOriginalAmountsByKeys(period, keys)) {
                Object bk = row.get("bizKey");
                Object amt = row.get("originalAmount");
                if (bk != null && amt != null) {
                    origMap.put(bk.toString(), new BigDecimal(amt.toString()));
                }
                Object adjusted = row.get("adjusted");
                if (bk != null && adjusted != null) {
                    adjMap.put(bk.toString(), Boolean.TRUE.equals(adjusted)
                        || "true".equalsIgnoreCase(String.valueOf(adjusted)));
                }
            }
            originalByPeriod.put(period, origMap);
            adjustedByPeriod.put(period, adjMap);

            // 新签（应收）「已调整」标记：跨期查 pexp 事实链（签约月可能与实收月不一致，故 period 传 null）
            Map<String, Boolean> expAdjMap = new HashMap<>();
            for (Map<String, Object> row : factMapper.selectOriginalFactAmountsByKeys(null, "PERF_EXPECT", keys)) {
                Object bk = row.get("bizKey");
                Object adjusted = row.get("adjusted");
                if (bk != null && adjusted != null) {
                    expAdjMap.put(bk.toString(), Boolean.TRUE.equals(adjusted)
                        || "true".equalsIgnoreCase(String.valueOf(adjusted)));
                }
            }
            expectedAdjustedByPeriod.put(period, expAdjMap);

            List<Map<String, Object>> pendings = receivedAdjustMapper.doSelectPendingByBizKeys(period, keys);
            Map<String, Map<String, Object>> pendMap = new HashMap<>();
            for (Map<String, Object> p : pendings) {
                String bk = p.get("bizKey") == null ? null : p.get("bizKey").toString();
                if (bk != null) {
                    pendMap.put(bk, p);
                }
            }
            pendingByPeriod.put(period, pendMap);
        }

        for (ReceivedApply apply : records) {
            String effContract = StringUtils.isBlank(apply.getContractNo()) ? "" : apply.getContractNo();
            String effOrder = StringUtils.isBlank(apply.getOrderNo()) ? "" : apply.getOrderNo();
            ReceivedContractMetricsVo m = metrics.get(metricsKey(apply.getPeriod(), effContract, effOrder));
            if (m != null) {
                // 业务类型优先用落库快照值；旧数据（列新增前建单）为空时回退实时聚合
                String bizType = StringUtils.isBlank(apply.getBizType()) ? m.getBizType() : apply.getBizType();
                apply.setBizType(bizType);
                apply.setEmployeeCount(m.getEmployeeCount());
                // 新签业绩展示实时值（含已生效调整），与详情/每人明细口径一致；
                // 「已调整」以新签事实链存在性为准（含 AMOUNT 类总额不变的重分配），
                // 兼容快照与实时不一致的旧口径，避免总额不变时漏标
                if (m.getExpectedAmount() != null) {
                    // 快照即「调整前」值，先留存再覆盖为实时值（口径同 getDetail），供前端展示「原值 → 调整后值」
                    apply.setOriginalExpectedAmount(apply.getExpectedAmount());
                    boolean expectedAdjusted = matchChainAdjusted(
                            expectedAdjustedByPeriod.get(apply.getPeriod()),
                            apply.getContractNo(), apply.getOrderNo())
                        || (apply.getExpectedAmount() != null
                            && apply.getExpectedAmount().compareTo(m.getExpectedAmount()) != 0);
                    apply.setExpectedAdjusted(expectedAdjusted);
                    apply.setExpectedAmount(m.getExpectedAmount());
                }
                // 实收业绩展示实时值（pj_received_detail ACTIVE 合计，含已生效实收调整）；
                // 「已调整」以链存在性为准（source_key 链存在 REVERSED 或 MANUAL-RADJ 新人行），
                // 调整前原值取 rd 链最早金额合计（含 REVERSED 历史；多次调整取最初，与每人明细同口径）；
                // AMOUNT 类调整总额不变时原值=现值，仅靠金额比较会漏标，故不以金额是否变作为判定
                if (m.getReceivedAmount() != null) {
                    BigDecimal originalReceived = matchChainOriginal(
                        originalByPeriod.get(apply.getPeriod()), apply.getContractNo(), apply.getOrderNo());
                    boolean receivedAdjusted = matchChainAdjusted(
                        adjustedByPeriod.get(apply.getPeriod()), apply.getContractNo(), apply.getOrderNo());
                    apply.setReceivedAdjusted(receivedAdjusted);
                    if (receivedAdjusted) {
                        apply.setOriginalReceivedAmount(
                            originalReceived != null ? originalReceived : m.getReceivedAmount());
                    }
                    apply.setReceivedAmount(m.getReceivedAmount());
                }
                // 审批中实收调整单标记：存在 SUBMITTED/APPROVED 单据时展示「调整审批中」+ 目标金额
                Map<String, Map<String, Object>> pendMap = pendingByPeriod.get(apply.getPeriod());
                if (pendMap != null) {
                    Map<String, Object> pend = matchAdjustByBizKey(pendMap, apply.getContractNo(), apply.getOrderNo());
                    if (pend != null) {
                        apply.setAdjustPending(true);
                        Object target = pend.get("targetAmount");
                        if (target != null) {
                            apply.setAdjustPendingAmount(new BigDecimal(target.toString()));
                        }
                    }
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

    /**
     * 按订单号优先、合同号兜底从链原值 Map 取该行的调整前实收合计。
     * <p>
     * {@code selectOriginalAmountsByKeys} 对同一张实收合同会同时按 order_no / contract_no
     * 两个键各输出一行（金额相同）；行有订单号时按订单号精确取（同合同号挂多订单防串单），
     * 查不到再退化合同号键。
     */
    private BigDecimal matchChainOriginal(Map<String, BigDecimal> origMap, String rowContractNo, String rowOrderNo) {
        if (origMap == null || origMap.isEmpty()) {
            return null;
        }
        if (StringUtils.isNotBlank(rowOrderNo) && origMap.containsKey(rowOrderNo)) {
            return origMap.get(rowOrderNo);
        }
        if (StringUtils.isNotBlank(rowContractNo)) {
            return origMap.get(rowContractNo);
        }
        return null;
    }

    /**
     * 按订单号优先、合同号兜底从「已调整」标记 Map 取该行的实收链调整标记
     * （匹配口径与 {@link #matchChainOriginal} 一致）。
     * <p>
     * 标记来源为 {@code selectOriginalAmountsByKeys.adjusted}：链上存在 REVERSED（supersede 冲销）
     * 或 MANUAL-RADJ 新人行即已调整，不受金额是否变化影响，避免 AMOUNT 类总额不变时漏标。
     *
     * @param adjustedMap period 内 bizKey → 是否已调整（可空）
     * @return true=该行实收链已调整；无匹配或未知按 false
     */
    private boolean matchChainAdjusted(Map<String, Boolean> adjustedMap,
                                       String rowContractNo, String rowOrderNo) {
        if (adjustedMap == null || adjustedMap.isEmpty()) {
            return false;
        }
        if (StringUtils.isNotBlank(rowOrderNo) && adjustedMap.containsKey(rowOrderNo)) {
            return Boolean.TRUE.equals(adjustedMap.get(rowOrderNo));
        }
        if (StringUtils.isNotBlank(rowContractNo) && adjustedMap.containsKey(rowContractNo)) {
            return Boolean.TRUE.equals(adjustedMap.get(rowContractNo));
        }
        return false;
    }

    /**
     * 按合同号/订单号匹配实收调整单（与 {@code PerformanceQueryServiceImpl.matchesBizRow} 同口径）。
     * <p>
     * 调整单 contract_no 存的是提交时的展示键（合同号或订单号，随入口而异），
     * 按订单号感知匹配：调整单有 order_no 时行的订单号必须相等；无 order_no 时退化按 bizKey 命中行合同号/订单号任一。
     */
    private Map<String, Object> matchAdjustByBizKey(Map<String, Map<String, Object>> adjustMap,
                                                     String rowContractNo, String rowOrderNo) {
        for (Map.Entry<String, Map<String, Object>> entry : adjustMap.entrySet()) {
            Map<String, Object> adj = entry.getValue();
            String adjOrder = adj.get("orderNo") == null ? null : adj.get("orderNo").toString();
            if (StringUtils.isNotBlank(adjOrder)) {
                if (StringUtils.isBlank(rowOrderNo) || !adjOrder.equals(rowOrderNo)) {
                    continue;
                }
                String adjContract = adj.get("bizKey") == null ? null : adj.get("bizKey").toString();
                if (adjContract != null
                    && (adjContract.equals(rowContractNo) || adjContract.equals(rowOrderNo))) {
                    return adj;
                }
                continue;
            }
            String bk = entry.getKey();
            if (bk.equals(rowContractNo)
                || (StringUtils.isNotBlank(rowOrderNo) && bk.equals(rowOrderNo))) {
                return adj;
            }
        }
        return null;
    }

    /** 组装 (合同号, 订单号) 复合键（两者均可空串占位）。 */
    private String metricsPairKey(String contractNo, String orderNo) {
        return contractNo + '|' + (orderNo == null ? "" : orderNo);
    }

    /** 组装 (期间, 合同号, 订单号) 复合键；期间为空或合同号/订单号全空返回空串（对应查不到，保持 null）。 */
    private String metricsKey(String period, String contractNo, String orderNo) {
        String ck = contractNo == null ? "" : contractNo;
        String ok = orderNo == null ? "" : orderNo;
        return StringUtils.isBlank(period) || (ck.isEmpty() && ok.isEmpty())
            ? "" : period + '|' + ck + '|' + ok;
    }

    @Override
    public ReceivedApplyDetail getDetail(Long id) {
        ReceivedApply apply = getAndCheck(id);
        // 应收是参照口径（非审批对象）：展示时实时取当前 ACTIVE PERF_EXPECT 合计（含已生效调整），
        // 与明细行「按合同+工号+角色实时配对」口径一致；仅内存覆盖，不落库。
        // 与提交时快照不一致时置「已调整」标记，让业务人员知道差额来自业绩调整。
        // 脏数据兼容：新签 order_no 误填成合同号时，顶部合计与每人明细统一经双侧闸门退化按合同号配对。
        boolean degradeExpect = StringUtils.isNotBlank(apply.getOrderNo())
            && canDegradeExpectToReceived(apply.getContractNo());
        if (StringUtils.isNotBlank(apply.getContractNo()) || StringUtils.isNotBlank(apply.getOrderNo())) {
            BigDecimal expected = sumExpectByBizKey(apply.getPeriod(), apply.getOrderNo(), apply.getContractNo());
            apply.setExpectedAdjusted(apply.getExpectedAmount() != null
                && apply.getExpectedAmount().compareTo(expected) != 0);
            // 保留快照供前端展示「调整前」，再覆盖为当前值
            apply.setOriginalExpectedAmount(apply.getExpectedAmount());
            apply.setExpectedAmount(expected);
        }
        List<ReceivedFactDetailVo> facts = factMapper.selectReceivedFactDetails(
            apply.getPeriod(), apply.getOrderNo(), apply.getContractNo(), degradeExpect);
        // 实收不再做折算；应收折算因子按合同业务类型取（整单同一因子）
        String bizType = apply.getBizType();
        Map<String, BigDecimal> factorMap = conversionFactorPort.factorsOf(
            bizType == null ? java.util.Collections.emptySet() : java.util.Collections.singleton(bizType));
        BigDecimal factor = conversionFactorPort.factorOf(factorMap, bizType);
        BigDecimal recvSum = BigDecimal.ZERO;
        BigDecimal originalRecvSum = BigDecimal.ZERO;
        for (ReceivedFactDetailVo f : facts) {
            // 应收折算（实收折算已取消）
            f.setExpectedConvertedAmount(conversionFactorPort.convert(f.getExpectedAmount(), factor));
            // 调整前应收的折算后金额：与当前值同一因子，仅在原值存在时输出（无调整则与原值一致，前端不展示）
            if (f.getOriginalExpectedAmount() != null) {
                f.setOriginalConvertedAmount(conversionFactorPort.convert(f.getOriginalExpectedAmount(), factor));
            }
            if (f.getAmount() != null) recvSum = recvSum.add(f.getAmount());
            // 实收行级原值（rd 链最早 REVERSED 金额，无链=当前值；MANUAL-RADJ 新人行=0）求和=合同原值合计
            originalRecvSum = originalRecvSum.add(f.getOriginalAmount() != null ? f.getOriginalAmount()
                : (f.getAmount() != null ? f.getAmount() : BigDecimal.ZERO));
        }
        // 实收合计取实收明细实时合计（rd ACTIVE，含已生效实收调整）；
        // 调整前原值=每人行链原值求和（多次调整取最初，与每人明细「原值 → 调整后值」同口径），
        // 与实时值不一致时置「已调整」；业绩调整（AMOUNT）总额不变时合计无箭头、仅个人行有箭头
        apply.setReceivedAmount(recvSum);
        boolean receivedAdjusted = originalRecvSum.compareTo(recvSum) != 0;
        apply.setReceivedAdjusted(receivedAdjusted);
        if (receivedAdjusted) {
            apply.setOriginalReceivedAmount(originalRecvSum);
        }
        // 应收折算后合计与「应收合计」同口径：合同新签总额 × 因子，
        // 不采用「每人明细 expectedConvertedAmount 求和」——同人同角色多行时分摊后虽等价，
        // 但直接以合同总额乘因子更直观，且避免分摊误差传导到合计。
        apply.setExpectedConvertedAmount(conversionFactorPort.convert(apply.getExpectedAmount(), factor));
        apply.setOriginalExpectedConvertedAmount(apply.getOriginalExpectedAmount() == null ? null
            : conversionFactorPort.convert(apply.getOriginalExpectedAmount(), factor));
        // 转人工审批原因（仅审批中）：人数不一致 / 实收不足，让审批人一眼知道卡点
        Set<Long> realEmps = facts.stream()
            .map(ReceivedFactDetailVo::getEmployeeId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        apply.setManualReviewReason(manualReviewReasonOf(apply, realEmps));
        return new ReceivedApplyDetail(apply, facts);
    }

    @Override
    public Long getInstanceId(Long id) {
        return approvalPort.instanceId(BizType.REAL_CONFIRM, id);
    }

    @Override
    public boolean claim(Long id) {
        ReceivedApply apply = applyMapper.selectById(id);
        if (apply == null) {
            throw new ServiceException("实收审批单不存在：" + id);
        }
        return approvalPort.claim(BizType.REAL_CONFIRM, id);
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
        // 撞唯一索引时原样抛 DuplicateKeyException，由外层建单循环 catch 后跳过（单条不炸整批）。
        // 严禁在此包装成 ServiceException——外层循环 catch (DuplicateKeyException) 接不住，
        // 会穿透导致整批回滚（2026-03 历史批次建单事件 FAILED 即此原因）。
        applyMapper.insert(apply);
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
        // PERF_EXPECT 应收合计（当月优先 + 双键/双侧闸门，与建单/新签补触发/详情一致）
        apply.setExpectedAmount(
            sumExpectByBizKey(apply.getPeriod(), apply.getOrderNo(), apply.getContractNo()));
        apply.setUpdateTime(LocalDateTime.now());
        applyMapper.updateById(apply);
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
        List<String> myNodes = collectMyApprovalNodes();
        if (myNodes.isEmpty()) {
            wrapper.ne(ReceivedApply::getStatus, ReceivedApplyStatus.SUBMITTED);
        } else {
            wrapper.and(w -> w.ne(ReceivedApply::getStatus, ReceivedApplyStatus.SUBMITTED)
                .or().in(ReceivedApply::getCurrentNode, myNodes));
        }
    }

    /** 同 {@link #applyApprovalNodeScope(LambdaQueryWrapper)}，字符串列版（全量汇总聚合查询共用）。 */
    private void applyApprovalNodeScope(QueryWrapper<ReceivedApply> wrapper) {
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        List<String> myNodes = collectMyApprovalNodes();
        if (myNodes.isEmpty()) {
            wrapper.ne("status", ReceivedApplyStatus.SUBMITTED);
        } else {
            wrapper.and(w -> w.ne("status", ReceivedApplyStatus.SUBMITTED)
                .or().in("current_node", myNodes));
        }
    }

    /** 当前登录用户角色对应的审批节点集合（FINANCE/DIRECTOR；无角色或无登录上下文返回空=不可见审批中单据）。 */
    private List<String> collectMyApprovalNodes() {
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
        return myNodes;
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
