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
import com.panjia.contracts.port.ImportBatchWritePort;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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

    /** 以订单号为业务键的业务类型；其余以合同号为准（与前端 resolveBizNo、SQL 聚合口径一致） */
    private static final Set<String> BIZ_TYPE_ORDER_FIRST = Set.of("一手房", "房产金融", "家装荐客");
    /** 导入问题类型：实收转人工审批（回写导入批次问题清单，非阻塞） */
    private static final String ISSUE_TYPE_MANUAL_APPROVAL = "RECEIVED_MANUAL_APPROVAL";
    /** 问题清单类型：实收到账无新签，明细已入库、未建单（与 ImportIssueType.RECEIVED_NO_EXPECT_PENDING 同码） */
    private static final String ISSUE_TYPE_NO_EXPECT_PENDING = "RECEIVED_NO_EXPECT_PENDING";
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
    /** 导入批次问题清单回写（归档阶段转人工审批的订单在批次页可见） */
    private final ImportBatchWritePort importBatchWritePort;
    /** 新签-实收人员对齐补行（与导入链路共用同一实现） */
    private final com.panjia.received.support.ReceivedSupplementSupport supplementSupport;

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
        // 去草稿：建单即提交（走工作流）；自动通过分支由调用方覆盖为 APPROVED
        apply.setStatus(ReceivedApplyStatus.SUBMITTED);
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
        // 去草稿：建单即提交（走工作流）；自动通过分支由调用方覆盖为 APPROVED
        apply.setStatus(ReceivedApplyStatus.SUBMITTED);
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
        // 订单号优先，查不到再用合同号兜底（新签联动建壳时 order_no 可能已被真实到账改写为真实订单号）
        List<com.panjia.received.domain.ReceivedContract> contracts =
            queryContractsByPeriodAndKey(period, orderNo, contractNo);
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
     * 按「归属月 + 订单号/合同号」查合同壳：订单号优先，查不到再用合同号兜底。
     * <p>真实实收到账复用虚拟壳后 order_no 已改为真实订单号，仅按 order_no 查会漏。
     */
    private List<com.panjia.received.domain.ReceivedContract> queryContractsByPeriodAndKey(
            String period, String orderNo, String contractNo) {
        LambdaQueryWrapper<com.panjia.received.domain.ReceivedContract> cw = new LambdaQueryWrapper<>();
        cw.eq(com.panjia.received.domain.ReceivedContract::getPeriod, period);
        if (StringUtils.isNotBlank(orderNo)) {
            cw.eq(com.panjia.received.domain.ReceivedContract::getOrderNo, orderNo);
        } else {
            cw.eq(com.panjia.received.domain.ReceivedContract::getContractNo, contractNo);
        }
        List<com.panjia.received.domain.ReceivedContract> contracts = contractMapper.selectList(cw);
        if (contracts.isEmpty() && StringUtils.isNotBlank(orderNo) && StringUtils.isNotBlank(contractNo)) {
            contracts = contractMapper.selectList(new LambdaQueryWrapper<com.panjia.received.domain.ReceivedContract>()
                .eq(com.panjia.received.domain.ReceivedContract::getPeriod, period)
                .eq(com.panjia.received.domain.ReceivedContract::getContractNo, contractNo));
        }
        return contracts;
    }

    /**
     * 收集 0 元联动实收单上可回绑的<b>纯虚拟调整行</b> ID。
     * <p>纯虚拟口径：ACTIVE 行全部无来源批次、无归一化记录、有 adjust_id，且净额为 0。
     * 空单返回空集合；存在任一非纯虚拟行返回 {@code null}（调用方跳过自动合并，交人工）。
     */
    private List<Long> collectPureVirtualDetailIds(Long applyId) {
        List<com.panjia.received.domain.ReceivedDetail> bound = detailMapper.selectList(
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
                .eq(com.panjia.received.domain.ReceivedDetail::getReceivedApplyId, applyId)
                .eq(com.panjia.received.domain.ReceivedDetail::getDetailStatus, "ACTIVE"));
        if (bound.isEmpty()) {
            return List.of();
        }
        BigDecimal net = BigDecimal.ZERO;
        for (com.panjia.received.domain.ReceivedDetail d : bound) {
            if (d.getSourceBatchId() != null || d.getNormalizedRecordId() != null || d.getAdjustId() == null) {
                return null;
            }
            net = net.add(nvlAmount(d.getPerformanceAmount()));
        }
        if (net.signum() != 0) {
            return null;
        }
        return bound.stream().map(com.panjia.received.domain.ReceivedDetail::getId).toList();
    }

    /**
     * 把 0 元联动单上的虚拟调整行回绑到新建的真实审批单，并把条数合并为「真实行 + 虚拟行」。
     * <p>实收金额只取真实到账合计（虚拟行净额 0，不回加），人员/角色口径由虚拟行保留。
     */
    private void carryOverVirtualDetails(ReceivedApply apply, List<Long> virtualDetailIds, int realRowCount) {
        if (virtualDetailIds == null || virtualDetailIds.isEmpty()) {
            return;
        }
        bindFacts(virtualDetailIds, apply.getId());
        apply.setItemCount(realRowCount + virtualDetailIds.size());
        applyMapper.updateById(apply);
        log.info("[实收审批] 0 元联动单虚拟调整行已回绑新单：applyId={}, orderNo={}, virtualRows={}, itemCount={}",
            apply.getId(), apply.getOrderNo(), virtualDetailIds.size(), apply.getItemCount());
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
     * <p>
     * 跨月累计（2026-10-29）：本月无活跃审批单时，把同订单<b>早于本月</b>的 DRAFT/SUBMITTED 单
     * 到账额与人员一并累计，累计 ≥ 跨月新签合计且人员一致 → 本月单与历史月单一起自动通过；
     * 历史 SUBMITTED 单先系统撤销流程，但<b>不各自发起结佣</b>——结佣统一由本月单按最新月发起。
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
        // ===== 4.1 跨月未处理实收批量预加载（同订单、早于当前期间的 SUBMITTED 单 + 未绑定明细） =====
        Map<String, List<ReceivedApply>> crossMonthApplies =
            loadCrossMonthUnhandled(factsByOrder.keySet(), period);
        Map<Long, Set<Long>> crossMonthEmpsByApplyId = loadRealEmployeesByApplyIds(
            crossMonthApplies.values().stream().flatMap(List::stream).map(ReceivedApply::getId).toList());
        // 先导实收、后到新签的到账：无审批单可查，累计口径改由未绑定 ACTIVE 明细承载
        Map<String, BigDecimal> priorUnboundAmountByOrder = new HashMap<>();
        Map<String, Set<Long>> priorUnboundEmpsByOrder = new HashMap<>();
        Map<String, Map<String, List<com.panjia.received.domain.ReceivedDetail>>> priorUnboundRowsByOrderPeriod =
            new HashMap<>();
        accumulatePriorUnboundReceived(factsByOrder.keySet(), period,
            priorUnboundAmountByOrder, priorUnboundEmpsByOrder, priorUnboundRowsByOrderPeriod);
        // ===== 5. 逐订单分流建单 =====
        int created = 0;
        int manualCreated = 0;
        int pendingNoExpect = 0;
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
                // 0 元联动单作废后需回绑到新单的虚拟调整行（纯虚拟壳场景，2026-10-31）
                List<Long> carryOverDetailIds = List.of();
                if (existing != null && existing.getStatus() == ReceivedApplyStatus.APPROVED) {
                    BigDecimal existingAmount = existing.getReceivedAmount();
                    if (existingAmount != null && existingAmount.signum() != 0) {
                        log.info("[实收审批] 贝壳实收订单当月审批单已 APPROVED，跳过：applyId={}, orderNo={}",
                            existing.getId(), orderNo);
                        continue;
                    }
                    // 1) 先收集 0 元单上的虚拟调整行（无来源批次、无归一化记录、有 adjustId 且净额 0）；
                    //    非纯虚拟（承载真实金额/真实到账行）时不做自动合并，交人工处理
                    carryOverDetailIds = collectPureVirtualDetailIds(existing.getId());
                    if (carryOverDetailIds == null) {
                        String msg = "订单 " + orderNo + " 存在 0 元实收单 " + existing.getApplyNo()
                            + "，其绑定明细非纯虚拟调整行，未自动合并，请人工处理";
                        log.warn("[实收审批] {}", msg);
                        importBatchWritePort.recordBatchIssue(batchId, ISSUE_TYPE_MANUAL_APPROVAL, msg);
                        continue;
                    }
                    // 2) 作废以释放 (period, order_no) 唯一键，让真实到账按正常分流重新建单
                    existing.setStatus(ReceivedApplyStatus.CANCELLED);
                    existing.setCurrentNode(null);
                    existing.setProcessInstanceId(null);
                    applyMapper.updateById(existing);
                    log.info("[实收审批] 0 元联动实收单已作废，放行真实到账建单：applyId={}, orderNo={}, 回绑虚拟行={}",
                        existing.getId(), orderNo, carryOverDetailIds.size());
                    existing = null;
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
                    if (existing.getStatus() == ReceivedApplyStatus.SUBMITTED) {
                        // 在审单并入本次到账：批次问题清单体现该订单仍卡在人工审批中
                        importBatchWritePort.recordBatchIssue(batchId, ISSUE_TYPE_MANUAL_APPROVAL,
                            "订单 " + orderNo + "，本次到账 " + amtText(realSum) + " 元已并入审批中的实收单 "
                                + existing.getApplyNo() + "（累计到账 " + amtText(existing.getReceivedAmount())
                                + " 元 / 新签 " + amtText(expectedAmount) + " 元），待人工审批");
                    }
                    continue;
                }
                boolean hasNewSign = expectedAmount != null && expectedAmount.signum() != 0;
                if (!hasNewSign) {
                    // 无新签（新签0元行不进系统，expectedAmount=0 等价无新签）：不建单（2026-10-29 去草稿）。
                    // 实收明细由导入侧照常入库、received_apply_id 留空即「待新签匹配」标记；
                    // 新签导入后由 PerformanceFactCreatedHandler → resolvePendingAfterNewSign 补建单。
                    // 注意：负数新签（红冲退单扣回）是有效业绩，signum()!=0 判定有新签，走下方正常审批流程
                    log.info("[实收审批] 订单无新签，仅落库不建单，待新签匹配：orderNo={}, received={}",
                        orderNo, realSum);
                    importBatchWritePort.recordBatchIssue(batchId, ISSUE_TYPE_NO_EXPECT_PENDING,
                        "订单 " + orderNo + "，本次到账 " + amtText(realSum)
                            + " 元，无新签，实收已入库未生成审批单（待新签导入后自动匹配）");
                    pendingNoExpect++;
                    continue;
                }
                ReceivedApply apply = newApplyFromContract(period, contract, batchId, operatorId,
                    rows.size(), realSum, expectedAmount, uniqueDeptId);
                // 合同维度判定：订单到账合计 vs 订单跨月新签合计；
                // 新增「新签-实收人数一致性」闸门：新签与实收的去重人员集合不一致（如新签 3 人、
                // 实收 2 人）时，即使实收金额 ≥ 新签金额也强制人工审批（卡审批中），不得自动通过。
                Set<Long> expectEmps = expectEmpsByOrder.getOrDefault(orderNo, Set.of());
                // 跨月未处理实收累计：同订单历史月份未处理到账（SUBMITTED 单 + 未绑定明细）的金额与人员
                // 一并纳入判定，避免分批到账（如 8 月到账 800 + 9 月到账 300、新签 1000）被逐月卡在人工审批
                List<ReceivedApply> crossMonth = crossMonthApplies.getOrDefault(orderNo, List.of());
                BigDecimal cumulativeReceived = realSum
                    .add(priorUnboundAmountByOrder.getOrDefault(orderNo, BigDecimal.ZERO));
                Set<Long> realEmps = new LinkedHashSet<>();
                rows.stream()
                    .map(com.panjia.received.domain.ReceivedDetail::getEmployeeId)
                    .filter(id -> id != null)
                    .forEach(realEmps::add);
                realEmps.addAll(priorUnboundEmpsByOrder.getOrDefault(orderNo, Set.of()));
                for (ReceivedApply old : crossMonth) {
                    cumulativeReceived = cumulativeReceived.add(nvlAmount(old.getReceivedAmount()));
                    realEmps.addAll(crossMonthEmpsByApplyId.getOrDefault(old.getId(), Set.of()));
                }
                boolean rosterMismatch = !expectEmps.isEmpty() && !expectEmps.equals(realEmps);
                if (rosterMismatch) {
                    log.info("[实收审批] 新签-实收人数不一致，转人工审批：orderNo={}, expectEmps={}, realEmps={}",
                        orderNo, expectEmps, realEmps);
                }
                boolean needManual = rosterMismatch || needManualReview(cumulativeReceived, expectedAmount);
                if (needManual) {
                    // 直接以 SUBMITTED 落库再起流程（2026-10-29 去草稿）：不再先插 DRAFT 再改状态，
                    // 避免出现「提交失败/流程启动异常」时残留一张无流程实例的草稿单
                    apply.setStatus(ReceivedApplyStatus.SUBMITTED);
                    insertApply(apply);
                    bindFacts(factIds, apply.getId());
                    carryOverVirtualDetails(apply, carryOverDetailIds, rows.size());
                    startWorkflow(apply, apply.getApplicantId(), Set.of());
                    log.info("[实收审批] 贝壳实收建单并提交人工审批：applyNo={}, orderNo={}, received={}",
                        apply.getApplyNo(), orderNo, apply.getReceivedAmount());
                    recordManualApprovalIssue(batchId, orderNo, contract, realSum, cumulativeReceived,
                        crossMonth.size(), expectedAmount, expectEmps, realEmps, apply);
                    manualCreated++;
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
                    carryOverVirtualDetails(apply, carryOverDetailIds, rows.size());
                    emitApprovedEvent(apply, operatorId, true);
                    log.info("[实收审批] 贝壳实收自动通过直建（实收保留到账金额）：applyNo={}, orderNo={}, received={}",
                        apply.getApplyNo(), orderNo, apply.getReceivedAmount());
                    // 跨月累计达标：连带把历史月份未处理实收单一起自动通过；
                    // 结佣只由本月单按最新月发起（结佣域跨期汇总未结佣新签与实收，一笔覆盖两次到账）
                    if (!crossMonth.isEmpty()) {
                        autoApproveCrossMonthApplies(crossMonth, operatorId, orderNo);
                    }
                    // 历史各归属月未绑定的到账：按各自归属月补建 APPROVED 单（不重发结佣事件），
                    // 否则那部分金额在实收审批列表里永远没有单据可查
                    approvePriorUnboundGroups(period, operatorId,
                        priorUnboundRowsByOrderPeriod.getOrDefault(orderNo, Map.of()));
                }
                created++;
            } catch (DuplicateKeyException e) {
                log.warn("[实收审批] 贝壳实收建单撞唯一索引，跳过：period={}, orderNo={}", period, orderNo);
            }
        }
        log.info("[实收审批] 贝壳实收分流建单完成：batchId={}, period={}, 新建={}, 转人工审批={}, 待新签未建单={}",
            batchId, period, created, manualCreated, pendingNoExpect);
        return created;
    }

    /**
     * 合同维度分流判定：返回 true = 需人工审批。
     * <p>正数场景：到账 &lt; 新签 → 人工；到账 ≥ 新签 → 自动通过。
     * <p>负数退单场景（2026-10-25 定稿）：负实收与负新签同为贝壳退单流水（全额退/部分退，
     * 且实收到账额可大于新签额），无需人工比对金额 → 自动通过并触发结佣；
     * 仅负实收却无负新签（数据异常，无冲减依据）→ 人工核对。
     */
    private static boolean needManualReview(BigDecimal arrival, BigDecimal expectTotal) {
        BigDecimal amt = arrival == null ? BigDecimal.ZERO : arrival;
        BigDecimal expect = expectTotal == null ? BigDecimal.ZERO : expectTotal;
        if (amt.signum() < 0 && expect.signum() < 0) {
            // 双负 = 同一笔退单的负实收 + 负新签，自动通过（不用人工审批退单）
            return false;
        }
        return amt.compareTo(expect) < 0;
    }

    /**
     * 回写「实收转人工审批」提示到导入批次问题清单（非阻塞）。
     * <p>让业务在批次页问题清单里直接看到本次导入中卡审批的订单、金额与原因。
     */
    private void recordManualApprovalIssue(Long batchId, String orderNo,
                                           com.panjia.received.domain.ReceivedContract contract,
                                           BigDecimal realSum, BigDecimal cumulativeReceived, int crossMonthCount,
                                           BigDecimal expectedAmount,
                                           Set<Long> expectEmps, Set<Long> realEmps,
                                           ReceivedApply apply) {
        if (batchId == null) {
            return;
        }
        StringBuilder msg = new StringBuilder("订单 ").append(orderNo);
        if (contract != null && StringUtils.isNotBlank(contract.getContractNo())) {
            msg.append(" / 合同 ").append(contract.getContractNo());
        }
        msg.append("，到账 ").append(amtText(realSum)).append(" 元");
        if (crossMonthCount > 0) {
            msg.append("（含跨月未审 ").append(crossMonthCount).append(" 单，累计 ")
                .append(amtText(cumulativeReceived)).append(" 元）");
        }
        msg.append("、新签 ").append(amtText(expectedAmount)).append(" 元");
        List<String> reasons = new ArrayList<>();
        if (realSum != null && realSum.signum() < 0
            && (expectedAmount == null || expectedAmount.signum() >= 0)) {
            reasons.add("负实收无对应负新签");
        }
        if (expectEmps != null && !expectEmps.isEmpty() && !expectEmps.equals(realEmps)) {
            reasons.add("人员不一致（新签 " + expectEmps.size() + " 人、实收 "
                + (realEmps == null ? 0 : realEmps.size()) + " 人）");
        }
        if (cumulativeReceived != null && cumulativeReceived.signum() >= 0
            && cumulativeReceived.compareTo(expectedAmount == null ? BigDecimal.ZERO : expectedAmount) < 0) {
            reasons.add("累计到账不足新签");
        }
        if (!reasons.isEmpty()) {
            msg.append("（").append(String.join("；", reasons)).append("）");
        }
        msg.append("，已转人工审批：实收单 ").append(apply.getApplyNo());
        importBatchWritePort.recordBatchIssue(batchId, ISSUE_TYPE_MANUAL_APPROVAL, msg.toString());
    }

    /** 金额展示文本（去尾零，避免科学计数法）。 */
    private static String amtText(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).stripTrailingZeros().toPlainString();
    }

    /**
     * 批量加载跨月未处理实收单（同订单号、期间早于当前期间的 SUBMITTED）。
     * <p>用于「未处理实收累计 ≥ 新签」自动通过判定：分批到账的跨月单据需合并金额一起比较。
     * <p>2026-10-29 去草稿后不再产生 DRAFT 单；「先导实收、后到新签」的未处理到账以
     * 「未绑定审批单的 ACTIVE 明细」承载，见 {@link #accumulatePriorUnboundReceived}。
     */
    private Map<String, List<ReceivedApply>> loadCrossMonthUnhandled(Collection<String> orderNos, String period) {
        if (orderNos == null || orderNos.isEmpty() || StringUtils.isBlank(period)) {
            return Map.of();
        }
        Map<String, List<ReceivedApply>> result = new HashMap<>();
        for (List<String> chunk : chunkKeys(orderNos)) {
            for (ReceivedApply apply : applyMapper.selectList(new LambdaQueryWrapper<ReceivedApply>()
                .in(ReceivedApply::getOrderNo, chunk)
                .eq(ReceivedApply::getStatus, ReceivedApplyStatus.SUBMITTED))) {
                String applyPeriod = apply.getPeriod();
                if (applyPeriod == null || applyPeriod.compareTo(period) >= 0) {
                    continue;
                }
                result.computeIfAbsent(apply.getOrderNo(), k -> new ArrayList<>()).add(apply);
            }
        }
        return result;
    }

    /**
     * 累计「历史月份未绑定审批单的 ACTIVE 实收明细」（去草稿后，先导实收、后到新签的到账就活在这里）。
     * <p>与跨月 SUBMITTED 单一起计入累计到账与人员集合，保证分批到账（8 月 800 + 9 月 300、新签 1000）
     * 仍能整体判定；否则先前落库的到账永远不会被纳入，导致足额也不能自动通过。
     */
    private void accumulatePriorUnboundReceived(Collection<String> orderNos, String period,
                                                Map<String, BigDecimal> amountByOrder,
                                                Map<String, Set<Long>> empsByOrder,
                                                Map<String, Map<String, List<com.panjia.received.domain.ReceivedDetail>>>
                                                    rowsByOrderPeriod) {
        if (orderNos == null || orderNos.isEmpty() || StringUtils.isBlank(period)) {
            return;
        }
        for (List<String> chunk : chunkKeys(orderNos)) {
            List<com.panjia.received.domain.ReceivedContract> priorContracts =
                contractMapper.selectList(new LambdaQueryWrapper<com.panjia.received.domain.ReceivedContract>()
                    .in(com.panjia.received.domain.ReceivedContract::getOrderNo, chunk)
                    .lt(com.panjia.received.domain.ReceivedContract::getPeriod, period));
            if (priorContracts.isEmpty()) {
                continue;
            }
            Map<Long, String> orderByContractId = new HashMap<>();
            for (com.panjia.received.domain.ReceivedContract c : priorContracts) {
                orderByContractId.put(c.getId(), c.getOrderNo());
            }
            for (com.panjia.received.domain.ReceivedDetail d : detailMapper.selectList(
                    new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
                        .in(com.panjia.received.domain.ReceivedDetail::getContractId,
                            orderByContractId.keySet())
                        .isNull(com.panjia.received.domain.ReceivedDetail::getReceivedApplyId)
                        .eq(com.panjia.received.domain.ReceivedDetail::getDetailStatus, "ACTIVE"))) {
                String orderNo = orderByContractId.get(d.getContractId());
                if (orderNo == null) {
                    continue;
                }
                amountByOrder.merge(orderNo, nvlAmount(d.getPerformanceAmount()), BigDecimal::add);
                if (d.getEmployeeId() != null) {
                    empsByOrder.computeIfAbsent(orderNo, k -> new LinkedHashSet<>()).add(d.getEmployeeId());
                }
                if (rowsByOrderPeriod != null && d.getPeriod() != null) {
                    rowsByOrderPeriod
                        .computeIfAbsent(orderNo, k -> new LinkedHashMap<>())
                        .computeIfAbsent(d.getPeriod(), k -> new ArrayList<>())
                        .add(d);
                }
            }
        }
    }

    /**
     * 跨月累计达标后，把历史各归属月的未绑定到账按其归属月单独补建 APPROVED 单（不触发结佣）。
     * <p>否则「8 月 800 + 9 月 300，新签 1000」只在 9 月批通过，8 月那 800 在实收审批列表里永远没有单。
     * <p>各月单均为系统自动通过（APPROVED、无流程实例）：金额已由当月订单整体判定认可，
     * 不重复发结佣事件（结佣只由本批达标单触发一次）。
     *
     * @return 补建单数量
     */
    private int approvePriorUnboundGroups(String period, Long operatorId,
            Map<String, List<com.panjia.received.domain.ReceivedDetail>> periodRows) {
        if (periodRows == null || periodRows.isEmpty()) {
            return 0;
        }
        int created = 0;
        for (Map.Entry<String, List<com.panjia.received.domain.ReceivedDetail>> periodEntry
                : periodRows.entrySet()) {
            String priorPeriod = periodEntry.getKey();
            List<com.panjia.received.domain.ReceivedDetail> rows = periodEntry.getValue();
            if (rows.isEmpty() || period.equals(priorPeriod)) {
                continue;
            }
            com.panjia.received.domain.ReceivedContract contract =
                contractMapper.selectById(rows.get(0).getContractId());
            if (contract == null) {
                continue;
            }
            // 该归属月已有在途/已通过单 → 不重复出单（订单号优先、无订单号按合同号）
            LambdaQueryWrapper<ReceivedApply> existsQ = new LambdaQueryWrapper<ReceivedApply>()
                .eq(ReceivedApply::getPeriod, priorPeriod)
                .in(ReceivedApply::getStatus,
                    ReceivedApplyStatus.SUBMITTED, ReceivedApplyStatus.APPROVED);
            if (StringUtils.isNotBlank(contract.getOrderNo())) {
                existsQ.eq(ReceivedApply::getOrderNo, contract.getOrderNo());
            } else {
                existsQ.eq(ReceivedApply::getContractNo, contract.getContractNo());
            }
            Long exists = applyMapper.selectCount(existsQ);
            if (exists != null && exists > 0) {
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (com.panjia.received.domain.ReceivedDetail d : rows) {
                sum = sum.add(nvlAmount(d.getPerformanceAmount()));
            }
            ReceivedApply apply = newApplyFromContract(priorPeriod, contract, null, operatorId,
                rows.size(), sum, null, contract.getDeptId());
            apply.setStatus(ReceivedApplyStatus.APPROVED);
            apply.setApproverId(operatorId);
            apply.setApproveTime(LocalDateTime.now());
            insertApply(apply);
            for (com.panjia.received.domain.ReceivedDetail d : rows) {
                d.setReceivedApplyId(apply.getId());
                detailMapper.updateById(d);
            }
            created++;
            log.info("[实收审批] 历史归属月未绑定到账单独建单：period={}, orderNo={}, rows={}, amount={}",
                priorPeriod, contract.getOrderNo(), rows.size(), sum);
        }
        return created;
    }

    /** 批量取审批单绑定的实收明细人员（ACTIVE），用于跨月累计的人员一致性校验。 */
    private Map<Long, Set<Long>> loadRealEmployeesByApplyIds(Collection<Long> applyIds) {
        if (applyIds == null || applyIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<Long>> result = new HashMap<>();
        for (com.panjia.received.domain.ReceivedDetail d : detailMapper.selectList(
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
                .in(com.panjia.received.domain.ReceivedDetail::getReceivedApplyId, applyIds)
                .eq(com.panjia.received.domain.ReceivedDetail::getDetailStatus, "ACTIVE"))) {
            if (d.getReceivedApplyId() == null || d.getEmployeeId() == null) {
                continue;
            }
            result.computeIfAbsent(d.getReceivedApplyId(), k -> new LinkedHashSet<>()).add(d.getEmployeeId());
        }
        return result;
    }

    /**
     * 跨月累计达标后连带通过历史未处理实收单。
     * <p>SUBMITTED 单先系统撤销运行中的流程实例（避免遗留财务/总监待办），再统一置 APPROVED。
     * <p><b>不 emit 通过事件</b>：结佣按「最新月」发起（本月单已 emit），结佣域按跨期
     * 汇总全部未结佣新签 + 跨期实收，一笔即可覆盖两个月的到账；历史单各自 emit 会
     * 让旧月各起一张结佣单，与"两笔到账都收到后按最新月结佣"的业务口径不符。
     * <p>逐单隔离：单张失败只记日志，不影响本月单据与整批归档。
     * <p>保护：已经产生人工审批记录（财务/总监办理过）的历史单不自动通过，保持原流程，
     * 避免系统撤销流程时丢失审批痕迹。
     */
    private void autoApproveCrossMonthApplies(List<ReceivedApply> applies, Long operatorId, String orderNo) {
        if (applies == null || applies.isEmpty()) {
            return;
        }
        // 已有人工办理痕迹的历史单：保持人工流程，不撤销、不置通过
        Set<Long> approverTouched;
        try {
            approverTouched = approvalPort.findApproverTouchedBizIds(
                applies.stream().map(ReceivedApply::getId).toList());
        } catch (Exception e) {
            log.warn("[实收审批] 跨月自动通过前查询人工办理痕迹失败，跳过历史单自动通过：orderNo={}, cause={}",
                orderNo, e.getMessage());
            return;
        }
        for (ReceivedApply old : applies) {
            if (approverTouched.contains(old.getId())) {
                log.info("[实收审批] 跨月累计达标，历史实收单已有人工审批记录，保持人工流程：applyNo={}, period={}, orderNo={}",
                    old.getApplyNo(), old.getPeriod(), orderNo);
                continue;
            }
            try {
                if (StringUtils.isNotBlank(old.getProcessInstanceId())) {
                    approvalPort.cancel(BizType.REAL_CONFIRM, old.getId());
                }
                // cancel 事件在同一事务内已置 CANCELLED，重新加载避免 @Version 乐观锁更新丢失
                ReceivedApply latest = applyMapper.selectById(old.getId());
                if (latest == null) {
                    continue;
                }
                latest.setStatus(ReceivedApplyStatus.APPROVED);
                latest.setCurrentNode(null);
                // 流程实例已软删（cancel 仅标记 del_flag），必须清空指针，
                // 否则批次撤销预检会把「APPROVED + 有流程实例」误判为人工审批通过而拦截
                latest.setProcessInstanceId(null);
                latest.setApproverId(operatorId);
                latest.setApproveTime(LocalDateTime.now());
                applyMapper.updateById(latest);
                // 不 emit：结佣统一由本月单按最新月发起（结佣域跨期汇总未结佣新签与实收）
                log.info("[实收审批] 跨月累计达标，历史实收单自动通过（结佣按最新月统一发起）："
                        + "applyNo={}, period={}, orderNo={}, received={}",
                    latest.getApplyNo(), latest.getPeriod(), orderNo, latest.getReceivedAmount());
            } catch (Exception e) {
                log.warn("[实收审批] 跨月累计达标，历史实收单自动通过失败：applyId={}, orderNo={}, cause={}",
                    old.getId(), orderNo, e.getMessage());
            }
        }
    }

    /** 发布实收审批通过事件（outbox 原子提交；contractNo 可为空，家装荐客等以 orderNo 为锚点）。 */
    private void emitApprovedEvent(ReceivedApply apply, Long operatorId, boolean autoApproved) {
        ReceivedApprovedEvent event = new ReceivedApprovedEvent();
        event.setApplyId(apply.getId());
        event.setPeriod(apply.getPeriod());
        event.setOrderNo(apply.getOrderNo());
        event.setContractNo(apply.getContractNo());
        event.setBizType(apply.getBizType());
        event.setOperatorId(operatorId);
        // 审批通过（系统自动/人工终审）均触发结佣域自动发起（二手买卖由消费方跳过）
        event.setAutoApproved(autoApproved);
        eventPort.emit(event);
    }

    @Override
    public void markAutoCommissionApply(Long applyId, String commissionApplyNo) {
        if (applyId == null || StringUtils.isBlank(commissionApplyNo)) {
            return;
        }
        // 直接 UPDATE 留痕，不走乐观锁版本号，避免与审批终态写入相互覆盖
        applyMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ReceivedApply>()
            .eq(ReceivedApply::getId, applyId)
            .set(ReceivedApply::getAutoCommissionApplyNo, commissionApplyNo));
    }

    @Override
    public com.panjia.contracts.dto.ReceivedContractInfoDTO findContractInfo(String contractNo, String orderNo) {
        if (StringUtils.isBlank(contractNo) && StringUtils.isBlank(orderNo)) {
            return null;
        }
        // 合同号优先（同合同多订单时逐单信息可能有差异，取最新一条）；
        // 合同号为空（一手房/家装荐客等）按订单号查
        com.panjia.received.domain.ReceivedContract rc = contractMapper.selectOne(
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedContract>()
                .eq(StringUtils.isNotBlank(contractNo), com.panjia.received.domain.ReceivedContract::getContractNo, contractNo)
                .eq(StringUtils.isBlank(contractNo) && StringUtils.isNotBlank(orderNo),
                    com.panjia.received.domain.ReceivedContract::getOrderNo, orderNo)
                .orderByDesc(com.panjia.received.domain.ReceivedContract::getUpdateTime)
                .last("LIMIT 1"));
        if (rc == null) {
            return null;
        }
        com.panjia.contracts.dto.ReceivedContractInfoDTO dto = new com.panjia.contracts.dto.ReceivedContractInfoDTO();
        dto.setOrderNo(rc.getOrderNo());
        dto.setContractNo(rc.getContractNo());
        dto.setBusinessDate(rc.getBusinessDate());
        dto.setPropertyAddress(rc.getPropertyAddress());
        return dto;
    }

    @Override
    public java.math.BigDecimal findReceivedAmount(String contractNo, String orderNo, String period) {
        if (StringUtils.isBlank(period) || (StringUtils.isBlank(contractNo) && StringUtils.isBlank(orderNo))) {
            return null;
        }
        // 口径：同合同（无合同号按订单号）+ 同实收期间，SUBMITTED/APPROVED 审批单合计；
        // 已驳回/已作废不计（驳回后实收金额可修正重提）；去草稿后不再有 DRAFT
        List<ReceivedApply> applies = applyMapper.selectList(new LambdaQueryWrapper<ReceivedApply>()
            .eq(ReceivedApply::getPeriod, period)
            .eq(StringUtils.isNotBlank(contractNo), ReceivedApply::getContractNo, contractNo)
            .eq(StringUtils.isBlank(contractNo) && StringUtils.isNotBlank(orderNo),
                ReceivedApply::getOrderNo, orderNo)
            .in(ReceivedApply::getStatus,
                ReceivedApplyStatus.SUBMITTED, ReceivedApplyStatus.APPROVED));
        BigDecimal fromApplies = applies.stream()
            .map(ReceivedApply::getReceivedAmount)
            .filter(java.util.Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 去草稿后「先导实收、待新签匹配」没有审批单，其到账同样属于该期实收合计，
        // 否则停在新签补录入口的订单会被算成 0 上限（补录校验失去意义）
        BigDecimal fromUnbound = BigDecimal.ZERO;
        List<com.panjia.received.domain.ReceivedContract> contracts = contractMapper.selectList(
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedContract>()
                .eq(com.panjia.received.domain.ReceivedContract::getPeriod, period)
                .eq(StringUtils.isNotBlank(contractNo),
                    com.panjia.received.domain.ReceivedContract::getContractNo, contractNo)
                .eq(StringUtils.isBlank(contractNo) && StringUtils.isNotBlank(orderNo),
                    com.panjia.received.domain.ReceivedContract::getOrderNo, orderNo));
        if (!contracts.isEmpty()) {
            List<Long> contractIds = contracts.stream()
                .map(com.panjia.received.domain.ReceivedContract::getId).toList();
            fromUnbound = detailMapper.selectList(new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
                    .in(com.panjia.received.domain.ReceivedDetail::getContractId, contractIds)
                    .eq(com.panjia.received.domain.ReceivedDetail::getPeriod, period)
                    .isNull(com.panjia.received.domain.ReceivedDetail::getReceivedApplyId)
                    .eq(com.panjia.received.domain.ReceivedDetail::getDetailStatus, "ACTIVE"))
                .stream()
                .map(d -> nvlAmount(d.getPerformanceAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        if (applies.isEmpty() && fromUnbound.signum() == 0) {
            return null;
        }
        return fromApplies.add(fromUnbound);
    }

    @Override
    public java.util.List<com.panjia.contracts.dto.ReceivedFactInfoDTO> findReceivedFacts(
            String contractNo, String orderNo, String period) {
        if (StringUtils.isBlank(period) || (StringUtils.isBlank(contractNo) && StringUtils.isBlank(orderNo))) {
            return java.util.Collections.emptyList();
        }
        // ① 合同号/订单号 → 实收合同主表 ID（同合同多期可能多条，全取）
        List<com.panjia.received.domain.ReceivedContract> contracts = contractMapper.selectList(
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedContract>()
                .eq(StringUtils.isNotBlank(contractNo), com.panjia.received.domain.ReceivedContract::getContractNo, contractNo)
                .eq(StringUtils.isBlank(contractNo) && StringUtils.isNotBlank(orderNo),
                    com.panjia.received.domain.ReceivedContract::getOrderNo, orderNo));
        if (contracts.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<Long> contractIds = contracts.stream()
            .map(com.panjia.received.domain.ReceivedContract::getId).distinct().toList();
        // ② 指定实收期间的 ACTIVE 明细（已红冲 REVERSED 行不展示）
        List<com.panjia.received.domain.ReceivedDetail> details = detailMapper.selectList(
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
                .in(com.panjia.received.domain.ReceivedDetail::getContractId, contractIds)
                .eq(com.panjia.received.domain.ReceivedDetail::getPeriod, period)
                .eq(com.panjia.received.domain.ReceivedDetail::getDetailStatus, "ACTIVE")
                .orderByAsc(com.panjia.received.domain.ReceivedDetail::getId));
        return details.stream().map(d -> {
            com.panjia.contracts.dto.ReceivedFactInfoDTO dto = new com.panjia.contracts.dto.ReceivedFactInfoDTO();
            dto.setEmployeeId(d.getEmployeeId());
            dto.setEmployeeExternalCode(d.getEmployeeExternalCode());
            dto.setRoleType(d.getRoleType());
            dto.setRoleName(d.getRoleName());
            dto.setShareRatio(d.getShareRatio());
            dto.setAmount(d.getPerformanceAmount());
            dto.setDeptId(d.getDeptId());
            return dto;
        }).toList();
    }

    private static BigDecimal nvlAmount(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    @Override
    public java.util.List<com.panjia.contracts.port.ReceivedApplyPort.PendingReceipt> findPendingReceipts(
            java.util.Collection<String> bizKeys) {
        if (bizKeys == null || bizKeys.isEmpty()) {
            return java.util.List.of();
        }
        Set<String> keys = bizKeys.stream()
            .filter(StringUtils::isNotBlank).map(String::trim)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (keys.isEmpty()) {
            return java.util.List.of();
        }
        List<com.panjia.contracts.port.ReceivedApplyPort.PendingReceipt> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<String> chunk : chunkKeys(keys)) {
            List<com.panjia.received.domain.ReceivedContract> contracts =
                contractMapper.selectList(new LambdaQueryWrapper<com.panjia.received.domain.ReceivedContract>()
                    .and(w -> w.in(com.panjia.received.domain.ReceivedContract::getOrderNo, chunk)
                        .or().in(com.panjia.received.domain.ReceivedContract::getContractNo, chunk)));
            if (contracts.isEmpty()) {
                continue;
            }
            Map<Long, com.panjia.received.domain.ReceivedContract> contractById = new HashMap<>();
            for (com.panjia.received.domain.ReceivedContract c : contracts) {
                contractById.put(c.getId(), c);
            }
            // 该批合同上「未绑定审批单的 ACTIVE 明细」→ 对应的 (订单号, 合同号, 归属月) 即待匹配到账
            Set<Long> pendingContractIds = detailMapper.selectList(
                    new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
                        .select(com.panjia.received.domain.ReceivedDetail::getContractId)
                        .in(com.panjia.received.domain.ReceivedDetail::getContractId, contractById.keySet())
                        .isNull(com.panjia.received.domain.ReceivedDetail::getReceivedApplyId)
                        .eq(com.panjia.received.domain.ReceivedDetail::getDetailStatus, "ACTIVE"))
                .stream()
                .map(com.panjia.received.domain.ReceivedDetail::getContractId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
            for (Long contractId : pendingContractIds) {
                com.panjia.received.domain.ReceivedContract c = contractById.get(contractId);
                if (c == null) {
                    continue;
                }
                // 去重键：统一业务键（一手房/房产金融/家装荐客订单号优先，其余合同号优先），同月多合同不能互相顶掉
                String bizKey = resolveBizKey(c.getBizType(), c.getOrderNo(), c.getContractNo());
                String dedupKey = (bizKey == null ? "" : bizKey) + "|" + c.getPeriod();
                if (seen.add(dedupKey)) {
                    result.add(new com.panjia.contracts.port.ReceivedApplyPort.PendingReceipt(
                        c.getOrderNo(), c.getContractNo(), c.getPeriod()));
                }
            }
        }
        return result;
    }

    /**
     * 新签到达后补建实收审批单（2026-10-29 去草稿）。
     * <p>「先导实收、后到新签」不再建草稿单：实收明细已落库且 {@code received_apply_id} 为空。
     * 新签导入触发本方法后：按订单（合同号兜底）取<b>该订单全部未绑定 ACTIVE 明细</b>作为累计到账，
     * 有新签则建单并走与导入时一致的自动通过/人工审批分流；无新签则保持未绑定，等下次触发。
     *
     * @return 是否已建单（自动通过或转人工）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean resolvePendingAfterNewSign(String period, String contractNo, String orderNo,
                                              Long operatorId, Long signBatchId) {
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

        // 1. 定位实收合同壳（三级回退）：订单号 → 合同号别名（合并前虚拟壳/历史按合同号建的壳）
        //    → 合同号且该合同非虚拟订单号唯一；避免新签 order_no 误填合同号时定位不到已重键的真实壳
        List<com.panjia.received.domain.ReceivedContract> contracts =
            resolvePendingContracts(contractNo, orderNo);
        if (contracts.isEmpty()) {
            return false;
        }

        // 2. 该锚点在该期间已有在途/已通过单 → 无需补建，避免重复出单
        //    （已作废/已驳回不算：作废会解绑明细，需重新出单；驳回单仍握有明细，下方按未绑定明细自然返回 false）
        Set<String> anchorOrderNos = contracts.stream()
            .map(com.panjia.received.domain.ReceivedContract::getOrderNo)
            .filter(StringUtils::isNotBlank).map(String::trim)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> anchorContractNos = contracts.stream()
            .map(com.panjia.received.domain.ReceivedContract::getContractNo)
            .filter(StringUtils::isNotBlank).map(String::trim)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (anchorOrderNos.isEmpty() && anchorContractNos.isEmpty()) {
            return false;
        }
        LambdaQueryWrapper<ReceivedApply> existsQ = new LambdaQueryWrapper<ReceivedApply>()
            .eq(ReceivedApply::getPeriod, period)
            .in(ReceivedApply::getStatus, ReceivedApplyStatus.SUBMITTED, ReceivedApplyStatus.APPROVED)
            .in(!anchorOrderNos.isEmpty(), ReceivedApply::getOrderNo, anchorOrderNos)
            .in(anchorOrderNos.isEmpty(), ReceivedApply::getContractNo, anchorContractNos);
        if (applyMapper.selectCount(existsQ) > 0) {
            return false;
        }

        // 3. 定位未绑定审批单的 ACTIVE 实收明细（锚点壳已按订单/合同号回退定位）
        Map<Long, com.panjia.received.domain.ReceivedContract> contractById = new HashMap<>();
        for (com.panjia.received.domain.ReceivedContract c : contracts) {
            contractById.put(c.getId(), c);
        }
        // 先导实收、后到新签：导入当时没有新签，补行动作无法执行；新签到达在这里补一次，
        // 否则人员不一致会把足额的订单强行卡到人工审批
        int supplemented = supplementSupport.supplement(contracts, period, null, signBatchId, operatorId);
        if (supplemented > 0) {
            for (com.panjia.received.domain.ReceivedContract c : contracts) {
                refreshContractAggregate(c.getId());
            }
        }
        List<com.panjia.received.domain.ReceivedDetail> rows = detailMapper.selectList(
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
                .in(com.panjia.received.domain.ReceivedDetail::getContractId, contractById.keySet())
                .isNull(com.panjia.received.domain.ReceivedDetail::getReceivedApplyId)
                .eq(com.panjia.received.domain.ReceivedDetail::getDetailStatus, "ACTIVE"));
        if (rows.isEmpty()) {
            return false;
        }
        // 按归属月拆行：本方法只为「本归属月」出单，历史归属月的到账在判定通过后按各自归属月单独补单，
        // 不再把多个月份折进一张单（否则单据归属月与到账月不符，按期间统计/退单匹配会偏移）
        Map<String, List<com.panjia.received.domain.ReceivedDetail>> rowsByPeriod = new LinkedHashMap<>();
        for (com.panjia.received.domain.ReceivedDetail d : rows) {
            rowsByPeriod.computeIfAbsent(d.getPeriod() != null ? d.getPeriod() : period,
                k -> new ArrayList<>()).add(d);
        }
        List<com.panjia.received.domain.ReceivedDetail> currentRows = rowsByPeriod.getOrDefault(period, List.of());
        if (currentRows.isEmpty()) {
            // 本次待匹配的归属月没有未绑定行（已被先前触发绑定）→ 无需出单
            return false;
        }
        // 建单锚点：取本归属月的合同
        com.panjia.received.domain.ReceivedContract anchor = contracts.stream()
            .filter(c -> period.equals(c.getPeriod()))
            .findFirst().orElse(contracts.get(0));
        // 判定口径：该订单未绑定到账全额（含历史归属月，支持跨月分批到账），金额入单只用本月行
        BigDecimal realSumAll = rows.stream()
            .map(r -> nvlAmount(r.getPerformanceAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal realSum = currentRows.stream()
            .map(r -> nvlAmount(r.getPerformanceAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Long> detailIds = currentRows.stream()
            .map(com.panjia.received.domain.ReceivedDetail::getId).toList();

        // 3. 新签合计（双键 + 双侧闸门，口径同 autoCreateForReceivedBatch）
        BigDecimal expectTotal = sumExpectByBizKey(period, orderNo, contractNo);
        if (expectTotal.signum() <= 0) {
            // 仍无新签：明细保持未绑定，等再次触发
            return false;
        }

        ReceivedApply apply = newApplyFromContract(period, anchor, null, operatorId,
            currentRows.size(), realSum, expectTotal, anchor.getDeptId());
        Set<Long> realEmps = rows.stream()
            .map(com.panjia.received.domain.ReceivedDetail::getEmployeeId)
            .filter(id -> id != null)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> expectEmps = expectEmpIdsByBizKey(period, orderNo, contractNo);
        boolean rosterMismatch = !expectEmps.isEmpty() && !expectEmps.equals(realEmps);
        if (realSumAll.compareTo(expectTotal) >= 0 && !rosterMismatch) {
            apply.setStatus(ReceivedApplyStatus.APPROVED);
            apply.setApproverId(operatorId);
            apply.setApproveTime(LocalDateTime.now());
            insertApply(apply);
            bindFacts(detailIds, apply.getId());
            emitApprovedEvent(apply, operatorId, true);
            log.info("[实收审批] 新签到达补建单并自动通过：applyNo={}, orderNo={}, received={}(本月 {}), expect={}, rows={}",
                apply.getApplyNo(), apply.getOrderNo(), realSumAll, realSum, expectTotal, currentRows.size());
            // 历史归属月未绑定到账按各自归属月单独补建 APPROVED 单（不重发结佣事件）
            approvePriorUnboundGroups(period, operatorId, rowsByPeriod);
            return true;
        }
        // 人工审批：把该订单全部未绑定到账（含历史归属月）一并交审，审批人看到完整到账
        apply.setItemCount(rows.size());
        apply.setReceivedAmount(realSumAll);
        apply.setStatus(ReceivedApplyStatus.SUBMITTED);
        insertApply(apply);
        bindFacts(rows.stream().map(com.panjia.received.domain.ReceivedDetail::getId).toList(),
            apply.getId());
        startWorkflow(apply, apply.getApplicantId(), Set.of());
        log.info("[实收审批] 新签到达补建单并提交人工审批：applyNo={}, orderNo={}, received={}, expect={},"
            + " rosterMismatch={}", apply.getApplyNo(), apply.getOrderNo(), realSumAll, expectTotal, rosterMismatch);
        return true;
    }

    /**
     * 新签到达补建单：定位实收合同壳（三级回退，均不限期间，交由明细按归属月过滤）。
     * <ol>
     *   <li>{@code order_no = 订单号}（正常路径）；</li>
     *   <li>{@code order_no = 合同号}（合并前虚拟壳 / 历史按合同号建的壳）；</li>
     *   <li>{@code contract_no = 合同号} 且该合同非虚拟订单号唯一（多订单禁止，防串单）。</li>
     * </ol>
     * 命中别名壳时若同合同下还存在真实壳，则改用真实壳，避免别名壳挡住待绑定的真实到账。
     */
    private List<com.panjia.received.domain.ReceivedContract> resolvePendingContracts(
            String contractNo, String orderNo) {
        String on = StringUtils.trimToNull(orderNo);
        String cn = StringUtils.trimToNull(contractNo);
        List<com.panjia.received.domain.ReceivedContract> contracts = List.of();
        if (on != null) {
            contracts = contractMapper.selectList(
                new LambdaQueryWrapper<com.panjia.received.domain.ReceivedContract>()
                    .eq(com.panjia.received.domain.ReceivedContract::getOrderNo, on));
        }
        if (contracts.isEmpty() && cn != null && !cn.equals(on)) {
            contracts = contractMapper.selectList(
                new LambdaQueryWrapper<com.panjia.received.domain.ReceivedContract>()
                    .eq(com.panjia.received.domain.ReceivedContract::getOrderNo, cn));
        }
        boolean aliasOnly = cn != null && !contracts.isEmpty()
            && contracts.stream().allMatch(c -> cn.equals(StringUtils.trimToNull(c.getOrderNo())));
        if (cn == null || contractMapper.countDistinctOrderNos(cn) != 1L) {
            // 多订单合同禁止按合同号/别名退化，避免串单；订单号精确命中的真实壳仍可用
            return aliasOnly ? List.of() : contracts;
        }
        List<com.panjia.received.domain.ReceivedContract> byContract = contractMapper.selectList(
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedContract>()
                .eq(com.panjia.received.domain.ReceivedContract::getContractNo, cn));
        if (contracts.isEmpty()) {
            return byContract;
        }
        boolean hasReal = byContract.stream()
            .anyMatch(c -> !cn.equals(StringUtils.trimToNull(c.getOrderNo())));
        return aliasOnly && hasReal ? byContract : contracts;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int revokeAutoApprovedApplies(String period, java.util.Collection<String> bizKeys) {
        if (StringUtils.isBlank(period) || bizKeys == null || bizKeys.isEmpty()) {
            return 0;
        }
        Set<String> keys = bizKeys.stream()
            .filter(StringUtils::isNotBlank).map(String::trim)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (keys.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (List<String> chunk : chunkKeys(keys)) {
            List<ReceivedApply> targets = applyMapper.selectList(new LambdaQueryWrapper<ReceivedApply>()
                .eq(ReceivedApply::getPeriod, period)
                .eq(ReceivedApply::getStatus, ReceivedApplyStatus.APPROVED)
                .isNull(ReceivedApply::getProcessInstanceId)
                .and(w -> w.in(ReceivedApply::getContractNo, chunk)
                    .or().in(ReceivedApply::getOrderNo, chunk)));
            for (ReceivedApply apply : targets) {
                // 明细回到「未绑定审批单」状态（待新签匹配），再删除自动通过单
                unbindFacts(apply.getId());
                applyMapper.deleteById(apply.getId());
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("[实收审批] 新签撤销，自动通过单已解绑并删除：period={}, count={}", period, deleted);
        }
        return deleted;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int revokeSupplements(Long signBatchId) {
        if (signBatchId == null) {
            return 0;
        }
        // 先记下受影响的合同，稍后按 ACTIVE 明细重算合计
        Set<Long> affectedContractIds = new LinkedHashSet<>();
        for (com.panjia.received.domain.ReceivedDetail d : detailMapper.selectList(
                new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
                    .eq(com.panjia.received.domain.ReceivedDetail::getSupplementOriginBatchId, signBatchId)
                    .select(com.panjia.received.domain.ReceivedDetail::getContractId))) {
            if (d.getContractId() != null) {
                affectedContractIds.add(d.getContractId());
            }
        }
        for (com.panjia.received.domain.ReceivedDetail d : detailMapper.selectList(
                new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
                    .eq(com.panjia.received.domain.ReceivedDetail::getSupersededByBatchId, signBatchId)
                    .select(com.panjia.received.domain.ReceivedDetail::getContractId))) {
            if (d.getContractId() != null) {
                affectedContractIds.add(d.getContractId());
            }
        }

        // 1. 删除该新签批次触发的补录行（未绑审批单才可删；已并入审批单的跳过，避免单据金额与明细不符）
        int removed = detailMapper.delete(new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
            .eq(com.panjia.received.domain.ReceivedDetail::getSupplementOriginBatchId, signBatchId)
            .eq(com.panjia.received.domain.ReceivedDetail::getDetailStatus, "ACTIVE")
            .isNull(com.panjia.received.domain.ReceivedDetail::getReceivedApplyId));

        // 2. 还原被该批压缩的原行（原金额完整保留在 SUPERSEDED 行上）
        com.panjia.received.domain.ReceivedDetail restore = new com.panjia.received.domain.ReceivedDetail();
        restore.setDetailStatus("ACTIVE");
        int restored = detailMapper.update(restore,
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedDetail>()
                .eq(com.panjia.received.domain.ReceivedDetail::getSupersededByBatchId, signBatchId)
                .eq(com.panjia.received.domain.ReceivedDetail::getDetailStatus, "SUPERSEDED"));
        detailMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.panjia.received.domain.ReceivedDetail>()
                .eq(com.panjia.received.domain.ReceivedDetail::getSupersededByBatchId, signBatchId)
                .set(com.panjia.received.domain.ReceivedDetail::getSupersededByBatchId, null));

        // 3. 受影响的合同合计按 ACTIVE 明细重算
        for (Long contractId : affectedContractIds) {
            refreshContractAggregate(contractId);
        }
        if (removed > 0 || restored > 0) {
            log.info("[实收审批] 新签撤销，补录已清理：signBatchId={}, removed={}, restored={}, contracts={}",
                signBatchId, removed, restored, affectedContractIds.size());
        }
        return removed + restored;
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
        // 实收侧：实收合同表 (合同号, 订单号, 来源) 去重对（全局，不限期间/批次）。
        // 排除新签调整联动虚拟壳（MANUAL 且 order_no=contract_no）：它不承载真实订单号，
        // 计入会让真实到账的唯一性判定恒为 2、退化匹配永久失效（2026-10-31）
        List<com.panjia.received.domain.ReceivedContract> receivedPairs = contractMapper.selectList(
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedContract>()
                .select(com.panjia.received.domain.ReceivedContract::getContractNo,
                    com.panjia.received.domain.ReceivedContract::getOrderNo,
                    com.panjia.received.domain.ReceivedContract::getSourceType)
                .in(com.panjia.received.domain.ReceivedContract::getContractNo, candidates)
                .isNotNull(com.panjia.received.domain.ReceivedContract::getOrderNo)
                .ne(com.panjia.received.domain.ReceivedContract::getOrderNo, "")
                .groupBy(com.panjia.received.domain.ReceivedContract::getContractNo,
                    com.panjia.received.domain.ReceivedContract::getOrderNo,
                    com.panjia.received.domain.ReceivedContract::getSourceType));
        Map<String, Integer> receivedCountByContract = new HashMap<>();
        for (com.panjia.received.domain.ReceivedContract c : receivedPairs) {
            if ("MANUAL".equals(c.getSourceType())
                && c.getContractNo() != null && c.getContractNo().equals(c.getOrderNo())) {
                continue;
            }
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
     * 按匹配键（订单号优先、合同号兜底）分片批量取当月活跃审批单（SUBMITTED/APPROVED，
     * 去草稿后不再有 DRAFT），归集到实收 orderNo 分组键，同一键取最新一张。贝壳自动建单判重用。
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
                    ReceivedApplyStatus.SUBMITTED, ReceivedApplyStatus.APPROVED))) {
                mergedById.putIfAbsent(apply.getId(), apply);
            }
        }
        for (List<String> chunk : chunkKeys(keys.orderOnlyKeys)) {
            for (ReceivedApply apply : applyMapper.selectList(new LambdaQueryWrapper<ReceivedApply>()
                .eq(ReceivedApply::getPeriod, period)
                .in(ReceivedApply::getOrderNo, chunk)
                .in(ReceivedApply::getStatus,
                    ReceivedApplyStatus.SUBMITTED, ReceivedApplyStatus.APPROVED))) {
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
     * 分片 IN 查询批量取指定订单号当月活跃审批单（SUBMITTED/APPROVED，去草稿后无 DRAFT），
     * 同一订单号取最新一张。手工提交 / 历史工资路径用（按 orderNo 口径）。
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
                    ReceivedApplyStatus.SUBMITTED, ReceivedApplyStatus.APPROVED)));
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
        if (apply.getStatus() != ReceivedApplyStatus.REJECTED) {
            throw new ServiceException("仅已驳回状态的审批单可重新提交（当前：" + apply.getStatus().getDesc() + "）");
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

    // ==================== 驳回后调整并重提 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReceivedApply reviseAndResubmit(Long id, com.panjia.received.domain.bo.ReceivedReviseBo bo) {
        ReceivedApply apply = getAndCheck(id);
        if (apply.getStatus() != ReceivedApplyStatus.REJECTED) {
            throw new ServiceException("仅已驳回状态的审批单可调整后重提（当前：" + apply.getStatus().getDesc() + "）");
        }
        if (bo == null || bo.getDetailTargets() == null || bo.getDetailTargets().isEmpty()) {
            throw new ServiceException("调整明细不能为空");
        }
        // 可调整范围与详情展示口径一致：本单已绑定明细 + 同合同同期未绑定的历史明细
        boolean degradeExpect = StringUtils.isNotBlank(apply.getOrderNo())
            && canDegradeExpectToReceived(apply.getContractNo());
        List<Long> factIds = factMapper.selectReceivedFactDetails(
                apply.getPeriod(), apply.getOrderNo(), apply.getContractNo(), degradeExpect, apply.getId())
            .stream()
            .map(ReceivedFactDetailVo::getFactId)
            .filter(java.util.Objects::nonNull)
            .toList();
        if (factIds.isEmpty()) {
            throw new ServiceException("该审批单暂无实收明细可调整");
        }
        Map<Long, com.panjia.received.domain.ReceivedDetail> editable = new HashMap<>();
        for (com.panjia.received.domain.ReceivedDetail d : detailMapper.selectBatchIds(factIds)) {
            if ("ACTIVE".equals(d.getDetailStatus())) {
                editable.put(d.getId(), d);
            }
        }
        Set<Long> touchedContractIds = new LinkedHashSet<>();
        int changedRows = 0;
        for (com.panjia.received.domain.bo.ReceivedReviseBo.Item item : bo.getDetailTargets()) {
            if (item == null || item.getFactId() == null) {
                throw new ServiceException("调整明细缺少明细 ID");
            }
            com.panjia.received.domain.ReceivedDetail detail = editable.get(item.getFactId());
            if (detail == null) {
                throw new ServiceException("实收明细不属于该审批单：" + item.getFactId());
            }
            boolean changed = false;
            if (item.getAmount() != null) {
                if (item.getAmount().signum() < 0) {
                    throw new ServiceException("调整后实收金额不能为负");
                }
                detail.setPerformanceAmount(item.getAmount());
                changed = true;
            }
            if (item.getShareRatio() != null) {
                if (item.getShareRatio().signum() < 0 || item.getShareRatio().compareTo(BigDecimal.ONE) > 0) {
                    throw new ServiceException("角色占比须在 0~1 之间");
                }
                detail.setShareRatio(item.getShareRatio());
                changed = true;
            }
            if (changed) {
                detailMapper.updateById(detail);
                changedRows++;
                if (detail.getContractId() != null) {
                    touchedContractIds.add(detail.getContractId());
                }
            }
        }
        if (changedRows == 0) {
            throw new ServiceException("无任何实收变动，无需调整");
        }
        refreshTotals(apply);
        refreshContractAggregates(touchedContractIds);
        log.info("[实收审批] 驳回后调整并重提：applyId={}, rows={}, reason={}", id, changedRows, bo.getReason());
        return resubmit(id);
    }

    // ==================== 作废 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id) {
        ReceivedApply apply = getAndCheck(id);
        if (apply.getStatus() != ReceivedApplyStatus.SUBMITTED) {
            throw new ServiceException("仅审批中的单据可作废（当前：" + apply.getStatus().getDesc() + "）");
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
        // period 可空：空 = 全部期间，同合同多期实收（如 6、7 月各一笔）一次批量审批全部 SUBMITTED 单
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
        Map<String, List<ReceivedApply>> submittedApplies = loadSubmittedAppliesBatch(period, deduped);
        List<ReceivedApply> applyList = submittedApplies.values().stream().flatMap(List::stream).distinct().toList();
        Map<Long, MyTaskBrief> myTaskMap = applyList.isEmpty()
            ? Map.of()
            : approvalPort.myCurrentTasks(BizType.REAL_CONFIRM,
                applyList.stream().map(ReceivedApply::getId).toList());
        // 按用户输入顺序组装可办任务，异步直接用 taskId 办理；
        // 同合同多期单据逐期各生成一个办理项（期间升序，先审早期月份），同一审批单去重防重复办理
        List<RcvApproveItem> approveItems = new ArrayList<>();
        Set<Long> addedApplyIds = new HashSet<>();
        for (String contractNo : deduped) {
            boolean actionable = false;
            for (ReceivedApply apply : submittedApplies.getOrDefault(contractNo, List.of())) {
                MyTaskBrief task = myTaskMap.get(apply.getId());
                if (task == null || !addedApplyIds.add(apply.getId())) {
                    continue;
                }
                approveItems.add(new RcvApproveItem(contractNo, apply, task));
                actionable = true;
            }
            if (!actionable) {
                result.getSkippedContracts().add(contractNo);
            }
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
     * 分片 IN 查询批量取指定合同 SUBMITTED 实收审批单（批量审批预检用）。
     * <p>period 为空时不限期间（跨期合并视图批量审批场景），同一合同可能命中多期单据，
     * 全部返回由调用方逐期办理；按输入字符串（合同号或订单号）双键建映射，
     * 列表内按期间升序（先审早期月份）、同期间按 ID 升序。
     */
    private Map<String, List<ReceivedApply>> loadSubmittedAppliesBatch(String period, Collection<String> contractNos) {
        if (contractNos.isEmpty()) {
            return Map.of();
        }
        List<ReceivedApply> applies = new ArrayList<>();
        for (List<String> chunk : chunkKeys(contractNos)) {
            applies.addAll(applyMapper.selectList(new LambdaQueryWrapper<ReceivedApply>()
                .eq(StringUtils.isNotBlank(period), ReceivedApply::getPeriod, period)
                .and(w -> w.in(ReceivedApply::getContractNo, chunk)
                    .or().in(ReceivedApply::getOrderNo, chunk))
                .eq(ReceivedApply::getStatus, ReceivedApplyStatus.SUBMITTED)));
        }
        applies.sort(Comparator.comparing(ReceivedApply::getPeriod, Comparator.nullsLast(String::compareTo))
            .thenComparing(ReceivedApply::getId));
        Map<String, List<ReceivedApply>> resultMap = new HashMap<>(contractNos.size() * 2);
        for (String input : contractNos) {
            List<ReceivedApply> matched = applies.stream()
                .filter(a -> input.equals(a.getContractNo()) || input.equals(a.getOrderNo()))
                .toList();
            if (!matched.isEmpty()) {
                resultMap.put(input, matched);
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
                // 人工审批通过：发布事件，结佣域除二手买卖外自动发起结佣（2026-10-29 改定）
                emitApprovedEvent(apply, handlerId, false);
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
        // 去草稿（2026-10-29）后无新签的到账不建单：由「未绑定审批单的 ACTIVE 明细」聚合出
        // 「待匹配新签」展示行（pendingMatch=true、status 为空、expectedAmount=0）。
        // 状态筛选 PENDING 时只返回这些展示行；筛其他状态时不返回（避免干扰审批口径）。
        boolean pendingOnly = "PENDING".equalsIgnoreCase(StringUtils.trimToEmpty(query.getStatus()));
        // 待匹配行只在「不看审批节点、不看退单」时混入：它没有流程节点，也不存在退单冲减
        boolean pendingAllowed = StringUtils.isBlank(query.getCurrentNode())
            && !"REFUND".equalsIgnoreCase(StringUtils.trimToEmpty(query.getRefundFilter()));
        List<ReceivedApply> pendingRows = pendingAllowed && query.getBatchId() == null
            && (pendingOnly || StringUtils.isBlank(query.getStatus()))
            ? buildPendingRows(query, effectiveDeptId) : new ArrayList<>();
        if (pendingOnly) {
            return pageRows(pendingRows, pageQuery, summaryOf(pendingRows));
        }
        // 分页查询：三个业绩列表统一排序（签约/认购时间倒序 → 业务键次序 → id 倒序兜底），
        // 业务键口径：一手房/房产金融/家装荐客订单号优先，其余合同号优先（与列表聚合口径一致）；
        // 不再按建单时间排（批量导入时建单时间集中且与业务发生顺序无关）
        QueryWrapper<ReceivedApply> wrapper = buildListCondition(query, effectiveDeptId);
        wrapper.orderByDesc("business_date")
            .last(", CASE WHEN biz_type IN ('一手房','房产金融','家装荐客') THEN COALESCE(NULLIF(order_no,''), NULLIF(contract_no,'')) ELSE COALESCE(NULLIF(contract_no,''), NULLIF(order_no,'')) END, id DESC");
        // 未选期间：同一合同/订单的 8、9 月单据合并为一行（详情按期间展开）；
        // 选定期：保持单据维度，退单月展示 TD 冲减单
        if (StringUtils.isBlank(query.getPeriod())) {
            return listMergedByContract(query, pageQuery, wrapper, pendingRows);
        }
        // 有待匹配展示行时需与单据行统一排序分页；为避免全量加载，只取排序后前「页码 × 页大小」条单据行，
        // 与待匹配行合并排序后切当前页（待匹配行只会把后续单据行后移，切页所需单据行数不超过该窗口）
        int pageNum = pageQuery.getPageNum() != null && pageQuery.getPageNum() > 0 ? pageQuery.getPageNum() : 1;
        int pageSize = pageQuery.getPageSize() != null && pageQuery.getPageSize() > 0 ? pageQuery.getPageSize() : 20;
        List<ReceivedApply> records;
        long applyTotal;
        if (pendingRows.isEmpty()) {
            Page<ReceivedApply> page = applyMapper.selectPage(pageQuery.build(), wrapper);
            records = page.getRecords();
            applyTotal = page.getTotal();
        } else {
            Page<ReceivedApply> page = applyMapper.selectPage(new Page<>(1, (long) pageNum * pageSize, true), wrapper);
            records = page.getRecords();
            applyTotal = page.getTotal();
        }
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

        if (pendingRows.isEmpty()) {
            PageResult<ReceivedApply> result = PageResult.build(records, applyTotal);
            Map<String, Object> only = new HashMap<>(4);
            only.put("totalReceivedAmount", totalReceivedAmount);
            only.put("totalExpectedAmount", totalExpectedAmount);
            only.put("totalExpectedConvertedAmount", totalExpectedConvertedAmount);
            result.setSummary(only);
            return result;
        }
        // 待匹配新签到账同样是实收：计入实收合计，并与单据行合并统一排序、分页
        for (ReceivedApply p : pendingRows) {
            totalReceivedAmount = totalReceivedAmount.add(nvlAmount(p.getReceivedAmount()));
        }
        List<ReceivedApply> rows = new ArrayList<>(records.size() + pendingRows.size());
        rows.addAll(records);
        rows.addAll(pendingRows);
        rows.sort(ReceivedApplyServiceImpl::compareRow);
        Map<String, Object> summary = new HashMap<>(4);
        summary.put("totalReceivedAmount", totalReceivedAmount);
        summary.put("totalExpectedAmount", totalExpectedAmount);
        summary.put("totalExpectedConvertedAmount", totalExpectedConvertedAmount);
        return pageRows(rows, pageQuery, summary, applyTotal + pendingRows.size());
    }

    /**
     * 未选期间时的合同维度列表：同合同/订单多期单据合并为一行
     * （8、9 月两笔 → 一行，期间列展示「2026-08、2026-09、2026-10」）；
     * 实收取各期净额（退单月负数相抵），新签取全部期间 ACTIVE 净额（{@link #fillMergedExpect}，
     * 全额退单后归零），逐期明细由 {@link #getContractDetail} 按期间展开。
     */
    private PageResult<ReceivedApply> listMergedByContract(ReceivedApplyBo query, PageQuery pageQuery,
                                                           QueryWrapper<ReceivedApply> wrapper,
                                                           List<ReceivedApply> pendingRows) {
        List<ReceivedApply> all = applyMapper.selectList(wrapper);
        if (all == null) {
            all = new ArrayList<>();
        }
        fillContractMetrics(all);
        fillManualReviewReason(all);
        // 按员工筛选：行金额改为该员工口径（先按单据换算，合并时再按期间求和）
        if (query.getEmployeeId() != null && !all.isEmpty()) {
            List<Object> applyIds = new ArrayList<>(all.size());
            for (ReceivedApply a : all) {
                applyIds.add(a.getId());
            }
            List<Map<String, Object>> empRows = detailMapper.selectEmployeeAmountsByApplyIds(applyIds, query.getEmployeeId());
            if (empRows == null) {
                empRows = java.util.Collections.emptyList();
            }
            Map<String, Map<String, Object>> empByApply = new HashMap<>();
            for (Map<String, Object> r : empRows) {
                empByApply.put(String.valueOf(r.get("applyId")), r);
            }
            Map<String, BigDecimal> rowFactorMap = conversionFactorPort.factorsOf(all.stream()
                .map(ReceivedApply::getBizType).filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet()));
            for (ReceivedApply a : all) {
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
        List<ReceivedApply> merged = mergeByContractKey(all);
        fillMergedExpect(merged);
        // 汇总与列表同口径：合并后行金额全量求和（跨页不随分页变化）
        BigDecimal totalReceivedAmount = BigDecimal.ZERO;
        BigDecimal totalExpectedAmount = BigDecimal.ZERO;
        BigDecimal totalExpectedConvertedAmount = BigDecimal.ZERO;
        for (ReceivedApply row : merged) {
            if (row.getReceivedAmount() != null) {
                totalReceivedAmount = totalReceivedAmount.add(row.getReceivedAmount());
            }
            if (row.getExpectedAmount() != null) {
                totalExpectedAmount = totalExpectedAmount.add(row.getExpectedAmount());
            }
            if (row.getExpectedConvertedAmount() != null) {
                totalExpectedConvertedAmount = totalExpectedConvertedAmount.add(row.getExpectedConvertedAmount());
            }
        }
        // 待匹配新签到账（无单据）同样是实收：并入实收合计，与合并行统一排序分页。
        // 待匹配行按归属月逐行展示（补录/出单都以「订单+月」为粒度），不与跨期合并行再合并。
        for (ReceivedApply p : pendingRows) {
            totalReceivedAmount = totalReceivedAmount.add(nvlAmount(p.getReceivedAmount()));
        }
        List<ReceivedApply> rows = new ArrayList<>(merged.size() + pendingRows.size());
        rows.addAll(merged);
        rows.addAll(pendingRows);
        rows.sort(ReceivedApplyServiceImpl::compareRow);
        Map<String, Object> summary = new HashMap<>(4);
        summary.put("totalReceivedAmount", totalReceivedAmount);
        summary.put("totalExpectedAmount", totalExpectedAmount);
        summary.put("totalExpectedConvertedAmount", totalExpectedConvertedAmount);
        return pageRows(rows, pageQuery, summary);
    }

    /**
     * 「待匹配新签」展示行：未绑定审批单的 ACTIVE 实收到账，按 (订单号, 归属月) 聚合。
     * <p>
     * 去草稿（2026-10-29）后无新签到账只落库不建单，列表据此补出展示行：
     * {@code pendingMatch=true}、{@code id/applyNo/status} 为空（不落库、不可审批）、
     * {@code expectedAmount=0}（新签为空即补录条件），其余取实收合同快照。
     */
    private List<ReceivedApply> buildPendingRows(ReceivedApplyBo query, Long effectiveDeptId) {
        List<Map<String, Object>> groups = detailMapper.selectPendingGroups(query.getPeriod(), effectiveDeptId,
            query.getKeyword(), query.getBizType(), query.getEmployeeId());
        if (groups == null || groups.isEmpty()) {
            return new ArrayList<>();
        }
        List<ReceivedApply> rows = new ArrayList<>(groups.size());
        for (Map<String, Object> g : groups) {
            ReceivedApply a = new ReceivedApply();
            a.setPendingMatch(true);
            a.setPeriod(mapStr(g, "period"));
            a.setContractNo(mapStr(g, "contractNo"));
            a.setOrderNo(mapStr(g, "orderNo"));
            a.setBizType(mapStr(g, "bizType"));
            a.setPropertyAddress(mapStr(g, "propertyAddress"));
            a.setBusinessDate(mapDateTime(g, "businessDate"));
            a.setReceivedAmount(mapDecimal(g, "receivedAmount"));
            a.setExpectedAmount(BigDecimal.ZERO);
            a.setExpectedConvertedAmount(BigDecimal.ZERO);
            a.setItemCount(mapInt(g, "itemCount"));
            a.setEmployeeCount(mapInt(g, "employeeCount"));
            rows.add(a);
        }
        return rows;
    }

    /** 待匹配展示行的汇总（只有实收：新签为 0，故折算后也为 0）。 */
    private Map<String, Object> summaryOf(List<ReceivedApply> rows) {
        BigDecimal received = BigDecimal.ZERO;
        for (ReceivedApply row : rows) {
            received = received.add(nvlAmount(row.getReceivedAmount()));
        }
        Map<String, Object> summary = new HashMap<>(4);
        summary.put("totalReceivedAmount", received);
        summary.put("totalExpectedAmount", BigDecimal.ZERO);
        summary.put("totalExpectedConvertedAmount", BigDecimal.ZERO);
        return summary;
    }

    /**
     * 统一业务键：一手房/房产金融/家装荐客订单号优先，其余合同号优先（各自为空回退另一列）。
     * 与前端 resolveBizNo、SQL 聚合口径一致。
     */
    private static String resolveBizKey(String bizType, String orderNo, String contractNo) {
        String order = StringUtils.trimToEmpty(orderNo);
        String contract = StringUtils.trimToEmpty(contractNo);
        if (BIZ_TYPE_ORDER_FIRST.contains(StringUtils.trimToEmpty(bizType))) {
            return order.isEmpty() ? contract : order;
        }
        return contract.isEmpty() ? order : contract;
    }

    /** 列表统一排序：签约/认购时间倒序 → 业务键次序 → 期间倒序 → id 倒序兜底。 */
    private static int compareRow(ReceivedApply a, ReceivedApply b) {
        LocalDateTime da = a.getBusinessDate();
        LocalDateTime db = b.getBusinessDate();
        if (da != null && db != null) {
            int c = db.compareTo(da);
            if (c != 0) {
                return c;
            }
        } else if (da != null) {
            return -1;
        } else if (db != null) {
            return 1;
        }
        String ka = resolveBizKey(a.getBizType(), a.getOrderNo(), a.getContractNo());
        String kb = resolveBizKey(b.getBizType(), b.getOrderNo(), b.getContractNo());
        int c = ka.compareTo(kb);
        if (c != 0) {
            return c;
        }
        int cp = StringUtils.trimToEmpty(b.getPeriod()).compareTo(StringUtils.trimToEmpty(a.getPeriod()));
        if (cp != 0) {
            return cp;
        }
        long ia = a.getId() == null ? Long.MIN_VALUE : a.getId();
        long ib = b.getId() == null ? Long.MIN_VALUE : b.getId();
        return Long.compare(ib, ia);
    }

    /** 内存分页（列表行由单据行 + 待匹配展示行合并而来，无法下推 DB 分页）。 */
    private PageResult<ReceivedApply> pageRows(List<ReceivedApply> rows, PageQuery pageQuery,
                                               Map<String, Object> summary) {
        return pageRows(rows, pageQuery, summary, rows.size());
    }

    /**
     * 内存分页（显式总数）：行集只取了排序窗口内的单据行，总数需由调用方按
     * 「单据总数 + 待匹配展示行数」传入，不能用 rows.size()。
     */
    private PageResult<ReceivedApply> pageRows(List<ReceivedApply> rows, PageQuery pageQuery,
                                               Map<String, Object> summary, long total) {
        int pageNum = pageQuery.getPageNum() != null ? pageQuery.getPageNum() : 1;
        int pageSize = pageQuery.getPageSize() != null ? pageQuery.getPageSize() : 20;
        int from = (int) Math.max(0, Math.min((long) (pageNum - 1) * pageSize, total));
        int to = (int) Math.min((long) from + pageSize, total);
        PageResult<ReceivedApply> result = PageResult.build(new ArrayList<>(rows.subList(from, to)), total);
        result.setSummary(summary);
        return result;
    }

    private static String mapStr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static BigDecimal mapDecimal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(v));
    }

    private static Integer mapInt(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : (v == null ? 0 : Integer.valueOf(String.valueOf(v)));
    }

    private static LocalDateTime mapDateTime(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (v instanceof java.time.OffsetDateTime odt) {
            return odt.toLocalDateTime();
        }
        if (v instanceof java.util.Date d) {
            return d.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        }
        return null;
    }

    /**
     * 同一合同/订单的多期审批单合并为一行（统一业务键；合同号/订单号都为空时各自成行）。
     * <p>
     * 实收取各期净额（跨期正负相抵，全额退单后归零）；新签（应收）为全部期间 ACTIVE 净额口径，
     * 由 {@link #fillMergedExpect} 批量覆盖（退单负数与历史正数相抵，全额退单后归零）。
     */
    private List<ReceivedApply> mergeByContractKey(List<ReceivedApply> all) {
        Map<String, List<ReceivedApply>> groups = new LinkedHashMap<>();
        for (ReceivedApply a : all) {
            String bizKey = resolveBizKey(a.getBizType(), a.getOrderNo(), a.getContractNo());
            String key = StringUtils.isBlank(bizKey) ? "I|" + a.getId() : "K|" + bizKey;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(a);
        }
        List<ReceivedApply> merged = new ArrayList<>(groups.size());
        for (List<ReceivedApply> group : groups.values()) {
            if (group.size() == 1) {
                merged.add(group.get(0));
                continue;
            }
            // 代表行：最近期间的非退单冲减单（TD 前缀为退单负镜像单），保留审批单号/状态留痕
            ReceivedApply base = null;
            for (ReceivedApply a : group) {
                if (StringUtils.isNotBlank(a.getApplyNo()) && a.getApplyNo().startsWith("TD")) {
                    continue;
                }
                if (base == null || StringUtils.trimToEmpty(a.getPeriod())
                    .compareTo(StringUtils.trimToEmpty(base.getPeriod())) >= 0) {
                    base = a;
                }
            }
            if (base == null) {
                base = group.get(0);
            }
            java.util.TreeSet<String> periods = new java.util.TreeSet<>();
            BigDecimal received = BigDecimal.ZERO;
            int itemCount = 0;
            int employeeCount = 0;
            for (ReceivedApply a : group) {
                if (StringUtils.isNotBlank(a.getPeriod())) {
                    periods.add(a.getPeriod());
                }
                if (a.getReceivedAmount() != null) {
                    received = received.add(a.getReceivedAmount());
                }
                itemCount += a.getItemCount() == null ? 0 : a.getItemCount();
                int cnt = a.getEmployeeCount() == null ? 0 : a.getEmployeeCount();
                employeeCount = Math.max(employeeCount, cnt);
            }
            // 实收取各期净额（跨期正负相抵），全额退单后归零，配合「已退单」标记展示；
            // 新签（应收）为全部期间 ACTIVE 净额口径，由 fillMergedExpect 统一批量覆盖
            base.setReceivedAmount(received);
            base.setItemCount(itemCount);
            base.setEmployeeCount(employeeCount);
            base.setPeriods(String.join("、", periods));
            base.setReceivedAdjusted(false);
            base.setOriginalReceivedAmount(null);
            merged.add(base);
        }
        return merged;
    }

    /**
     * 合并行的新签（应收）口径：全部期间 ACTIVE 新签净额（按 period=null 批量取，
     * metrics SQL 合同粒度净额分支），退单负数与历史正数相抵，全额退单后归零。
     */
    private void fillMergedExpect(List<ReceivedApply> rows) {
        List<ReceivedApply> targets = new ArrayList<>();
        Map<String, Map<String, String>> keyMap = new LinkedHashMap<>();
        // 退化判定走批量闸门（1 条分组 SQL），避免按行回表
        Set<String> preciseContracts = rows.stream()
            .filter(r -> StringUtils.isNotBlank(r.getPeriods())
                && StringUtils.isNotBlank(r.getContractNo()) && StringUtils.isNotBlank(r.getOrderNo()))
            .map(ReceivedApply::getContractNo)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> degradableContracts = globallyDegradableContracts(preciseContracts);
        for (ReceivedApply row : rows) {
            if (StringUtils.isBlank(row.getPeriods())) {
                continue;
            }
            String contractNo = StringUtils.isBlank(row.getContractNo()) ? "" : row.getContractNo();
            String orderNo = StringUtils.isBlank(row.getOrderNo()) ? "" : row.getOrderNo();
            if (contractNo.isEmpty() && orderNo.isEmpty()) {
                continue;
            }
            Map<String, String> key = new HashMap<>(3);
            key.put("contractNo", contractNo);
            key.put("orderNo", orderNo);
            key.put("degrade", !contractNo.isEmpty() && !orderNo.isEmpty()
                && degradableContracts.contains(row.getContractNo()) ? "true" : "false");
            keyMap.putIfAbsent(metricsPairKey(contractNo, orderNo), key);
            targets.add(row);
        }
        if (targets.isEmpty()) {
            return;
        }
        Map<String, ReceivedContractMetricsVo> metrics = new HashMap<>();
        for (ReceivedContractMetricsVo m : factMapper.selectReceivedContractMetrics(null, keyMap.values())) {
            metrics.put(metricsPairKey(m.getContractNo(), StringUtils.isBlank(m.getOrderNo()) ? "" : m.getOrderNo()), m);
        }
        Map<String, BigDecimal> factorMap = conversionFactorPort.factorsOf(targets.stream()
            .map(ReceivedApply::getBizType).filter(StringUtils::isNotBlank).collect(Collectors.toSet()));
        for (ReceivedApply row : targets) {
            ReceivedContractMetricsVo m = metrics.get(metricsPairKey(
                StringUtils.isBlank(row.getContractNo()) ? "" : row.getContractNo(),
                StringUtils.isBlank(row.getOrderNo()) ? "" : row.getOrderNo()));
            if (m == null) {
                continue;
            }
            BigDecimal expected = m.getExpectedAmount() == null ? BigDecimal.ZERO : m.getExpectedAmount();
            row.setExpectedAmount(expected);
            row.setExpectedConvertedAmount(conversionFactorPort.convert(expected,
                conversionFactorPort.factorOf(factorMap, row.getBizType())));
            row.setExpectedAdjusted(false);
            row.setOriginalExpectedAmount(null);
            row.setOriginalExpectedConvertedAmount(null);
        }
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
            // 无员工归属的单据（贝壳实收空角色行按合同级入库，明细 employee_id/工号均为空）：
            // 按员工部门永远命中不了，会被部门筛选永久隐藏（待办能看到、驳回后列表找不到）。
            // 故对「全部 ACTIVE 明细都无员工」的单据不受部门过滤，其余仍严格按部门子树隔离。
            wrapper.apply(
                "(EXISTS (SELECT 1 FROM pj_received_detail rd"
                    + " JOIN pj_people_employee e ON (e.employee_id = rd.employee_id"
                    + "   OR (NOT EXISTS (SELECT 1 FROM pj_people_employee x WHERE x.employee_id = rd.employee_id)"
                    + "       AND e.employee_code = rd.employee_external_code))"
                    + " WHERE rd.received_apply_id = pj_perf_received_apply.id"
                    + " AND rd.detail_status = 'ACTIVE'"
                    + " AND (e.dept_id = {0}"
                    + " OR e.dept_id IN (SELECT sd.dept_id FROM sys_dept sd"
                    + " WHERE sd.ancestors LIKE CONCAT('%', {0}, '%'))))"
                    + " OR NOT EXISTS (SELECT 1 FROM pj_received_detail rd0"
                    + " WHERE rd0.received_apply_id = pj_perf_received_apply.id"
                    + " AND rd0.detail_status = 'ACTIVE'"
                    + " AND (rd0.employee_id IS NOT NULL"
                    + "   OR COALESCE(rd0.employee_external_code, '') <> '')))",
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
        // 退单三态筛选：选定期间（单据维度）按行过滤退单行——判定兼容两类：
        // 手工退单生成的 TD 前缀冲减单、贝壳导入的负金额实收单（RCV 前缀 + 负数，无 TD 标记）；
        // 未选期间（同合同多期单据合并为一行）无法按行过滤，改按「该合同是否存在退单行」EXISTS 过滤
        String refundFilter = query.getRefundFilter();
        if (StringUtils.isNotBlank(refundFilter)) {
            if (StringUtils.isNotBlank(query.getPeriod())) {
                if ("REFUND".equals(refundFilter)) {
                    wrapper.apply("(apply_no LIKE 'TD%' OR received_amount < 0)");
                } else if ("NORMAL".equals(refundFilter)) {
                    wrapper.apply("apply_no NOT LIKE 'TD%' AND received_amount >= 0");
                }
            } else {
                String tdExists = "EXISTS (SELECT 1 FROM pj_perf_received_apply td"
                    + " WHERE (td.apply_no LIKE 'TD%' OR td.received_amount < 0) AND td.status != 'CANCELLED'"
                    + " AND (td.contract_no = pj_perf_received_apply.contract_no"
                    + " OR (td.order_no IS NOT NULL AND td.order_no != ''"
                    + " AND td.order_no = pj_perf_received_apply.order_no)))";
                if ("REFUND".equals(refundFilter)) {
                    wrapper.apply(tdExists);
                } else if ("NORMAL".equals(refundFilter)) {
                    wrapper.apply("NOT " + tdExists);
                }
            }
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
                // 键含单据 ID：同合同同期间的正常单与退单 TD 冲减单各自归属自身明细金额；
                // 空 applyId 行为历史未绑单明细的合同粒度聚合（lookup 兜底用）
                metrics.put(metricsKey(entry.getKey(), row.getContractNo(),
                    StringUtils.isBlank(row.getOrderNo()) ? "" : row.getOrderNo())
                    + "|" + (row.getApplyId() == null ? "" : row.getApplyId()), row);
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
            String mKey = metricsKey(apply.getPeriod(), effContract, effOrder);
            // 单据粒度精确匹配；历史明细未绑单（received_apply_id 为空）时回退合同粒度聚合行
            ReceivedContractMetricsVo m = metrics.get(mKey + "|" + (apply.getId() == null ? "" : apply.getId()));
            if (m == null) {
                m = metrics.get(mKey + "|");
            }
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
            apply.getPeriod(), apply.getOrderNo(), apply.getContractNo(), degradeExpect, apply.getId());
        return assemble(apply, mergeFactRows(facts, false));
    }

    /**
     * 「待匹配新签」组的每人明细：未绑定审批单的 ACTIVE 实收明细（去草稿后无新签到账不建单）。
     */
    @Override
    public List<ReceivedFactDetailVo> listPendingDetails(String bizKey, String period) {
        if (StringUtils.isBlank(bizKey)) {
            throw new ServiceException("订单号/合同号不能为空");
        }
        return detailMapper.selectPendingDetails(bizKey, period);
    }

    /**
     * 按合同/订单跨期间查询实收明细：基准单取最近一张非退单冲减单（保留审批单号/状态留痕），
     * 明细覆盖全部期间的 ACTIVE 实收事实，按（员工+角色+期间）逐月分行展示
     * （同月多笔部分实收合并为一行；退单红冲冲减行按月独立成行，负数展示）。
     */
    @Override
    public ReceivedApplyDetail getContractDetail(String contractNo, String orderNo) {
        if (StringUtils.isBlank(contractNo) && StringUtils.isBlank(orderNo)) {
            throw new ServiceException("合同号与订单号不能同时为空");
        }
        List<ReceivedApply> applies = applyMapper.selectList(new LambdaQueryWrapper<ReceivedApply>()
            .eq(StringUtils.isNotBlank(contractNo), ReceivedApply::getContractNo, contractNo)
            .eq(StringUtils.isNotBlank(orderNo), ReceivedApply::getOrderNo, orderNo)
            .ne(ReceivedApply::getStatus, ReceivedApplyStatus.CANCELLED)
            .orderByAsc(ReceivedApply::getPeriod)
            .orderByAsc(ReceivedApply::getId));
        if (applies == null || applies.isEmpty()) {
            throw new ServiceException("该合同暂无实收单据");
        }
        // 基准单：最近期间的非退单冲减单（退单冲减单 applyNo 以 TD 开头，金额为负数镜像）
        ReceivedApply base = null;
        for (ReceivedApply a : applies) {
            if (StringUtils.isNotBlank(a.getApplyNo()) && a.getApplyNo().startsWith("TD")) {
                continue;
            }
            if (base == null || StringUtils.trimToEmpty(a.getPeriod())
                .compareTo(StringUtils.trimToEmpty(base.getPeriod())) >= 0) {
                base = a;
            }
        }
        if (base == null) {
            base = applies.get(applies.size() - 1);
        }
        // 期间串：全部单据期间去重升序（头部「期间」展示，如「2026-08、2026-09、2026-10」）
        java.util.TreeSet<String> periodSet = new java.util.TreeSet<>();
        for (ReceivedApply a : applies) {
            if (StringUtils.isNotBlank(a.getPeriod())) {
                periodSet.add(a.getPeriod());
            }
        }
        // 应收口径：全部期间 ACTIVE 新签净额（退单月负数与历史正数相抵），
        // 与列表合并行同口径；单期详情仍走「当月优先」口径（退单月显示本次冲减金额）
        boolean degradeExpect = StringUtils.isNotBlank(base.getOrderNo())
            && canDegradeExpectToReceived(base.getContractNo());
        String baseContractNo = StringUtils.isBlank(base.getContractNo()) ? "" : base.getContractNo();
        String baseOrderNo = StringUtils.isBlank(base.getOrderNo()) ? "" : base.getOrderNo();
        if (!baseContractNo.isEmpty() || !baseOrderNo.isEmpty()) {
            Map<String, String> key = new HashMap<>(3);
            key.put("contractNo", baseContractNo);
            key.put("orderNo", baseOrderNo);
            key.put("degrade", degradeExpect ? "true" : "false");
            BigDecimal expected = BigDecimal.ZERO;
            for (ReceivedContractMetricsVo m : factMapper.selectReceivedContractMetrics(null, List.of(key))) {
                if (m.getExpectedAmount() != null) {
                    expected = m.getExpectedAmount();
                }
            }
            base.setExpectedAdjusted(base.getExpectedAmount() != null
                && base.getExpectedAmount().compareTo(expected) != 0);
            base.setOriginalExpectedAmount(base.getExpectedAmount());
            base.setExpectedAmount(expected);
        }
        List<ReceivedFactDetailVo> facts = factMapper.selectReceivedFactDetails(
            null, base.getOrderNo(), base.getContractNo(), degradeExpect, null);
        for (ReceivedFactDetailVo f : facts) {
            if (StringUtils.isNotBlank(f.getPeriod())) {
                periodSet.add(f.getPeriod());
            }
        }
        base.setPeriods(String.join("、", periodSet));
        return assemble(base, mergeFactRows(facts, true));
    }

    /**
     * 明细行合并：按月分行展示，与新签明细一致——
     * 同月同员工同角色的多笔部分实收合并为一行；跨期合同详情中退单红冲冲减行
     * 按月独立成行（期间=退单落账月，负数展示），不与正常行跨期合并、也不与同月正常行互抵。
     * <p>
     * 各行「对应新签」保留 SQL 行级口径：正数行=该实收期间及之前累计，
     * 负数行=当期负部新签（本次冲减对应的新签退单）；
     * 与选定期间后的单期详情逐月口径完全一致。
     */
    private List<ReceivedFactDetailVo> mergeFactRows(List<ReceivedFactDetailVo> facts, boolean crossPeriod) {
        Map<String, ReceivedFactDetailVo> merged = new LinkedHashMap<>();
        for (ReceivedFactDetailVo f : facts) {
            String key = String.valueOf(f.getEmployeeCode()) + "|" + String.valueOf(f.getRoleType())
                + "|" + String.valueOf(f.getPeriod())
                + (crossPeriod && isRefundFactRow(f) ? "|R" : "");
            ReceivedFactDetailVo m = merged.get(key);
            if (m == null) {
                merged.put(key, f);
                continue;
            }
            if (m.getAmount() != null || f.getAmount() != null) {
                m.setAmount((m.getAmount() == null ? BigDecimal.ZERO : m.getAmount())
                    .add(f.getAmount() == null ? BigDecimal.ZERO : f.getAmount()));
            }
            if (m.getOriginalAmount() != null || f.getOriginalAmount() != null) {
                m.setOriginalAmount((m.getOriginalAmount() == null ? BigDecimal.ZERO : m.getOriginalAmount())
                    .add(f.getOriginalAmount() == null ? BigDecimal.ZERO : f.getOriginalAmount()));
            }
            if (Boolean.TRUE.equals(f.getReceivedAdjusted())) m.setReceivedAdjusted(true);
            if (Boolean.TRUE.equals(f.getExpectedAdjusted())) m.setExpectedAdjusted(true);
        }
        return new java.util.ArrayList<>(merged.values());
    }

    /** 退单冲减行判定：归属 TD 前缀冲减单的明细；历史未绑单明细以金额正负兜底。 */
    private static boolean isRefundFactRow(ReceivedFactDetailVo f) {
        if (StringUtils.isNotBlank(f.getApplyNo())) {
            return f.getApplyNo().startsWith("TD");
        }
        return f.getAmount() != null && f.getAmount().signum() < 0;
    }

    /** 明细行折算、合计与审批提示组装（单期详情与跨期合同详情共用）。 */
    private ReceivedApplyDetail assemble(ReceivedApply apply, List<ReceivedFactDetailVo> facts) {
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

    /** 按 ACTIVE 明细分组合计重算合同级实收合计/条数（与导入、撤销链路同口径）。 */
    private void refreshContractAggregates(java.util.Collection<Long> contractIds) {
        if (contractIds == null || contractIds.isEmpty()) {
            return;
        }
        Map<Long, Map<String, Object>> aggregateByContract = new LinkedHashMap<>();
        for (Map<String, Object> row : detailMapper.selectActiveAggregatesByContractIds(contractIds)) {
            aggregateByContract.put(((Number) row.get("contractId")).longValue(), row);
        }
        List<com.panjia.received.domain.ReceivedContract> contracts = contractMapper.selectList(
            new LambdaQueryWrapper<com.panjia.received.domain.ReceivedContract>()
                .in(com.panjia.received.domain.ReceivedContract::getId, contractIds));
        for (com.panjia.received.domain.ReceivedContract c : contracts) {
            Map<String, Object> agg = aggregateByContract.get(c.getId());
            Object total = agg == null ? null : agg.get("totalAmount");
            Object count = agg == null ? null : agg.get("itemCount");
            c.setPeriodTotalReceived(total == null ? BigDecimal.ZERO : new BigDecimal(total.toString()));
            c.setItemCount(count == null ? 0 : ((Number) count).intValue());
            contractMapper.updateById(c);
        }
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
        // 复用同期间同合同的唯一真实壳：新签 order_no 误填合同号时不再新建第三个「合同号壳」
        String cn = StringUtils.trimToNull(expect.getContractNo());
        if (cn != null && contractMapper.countDistinctOrderNos(cn) == 1L) {
            List<com.panjia.received.domain.ReceivedContract> shells = contractMapper.selectList(
                new LambdaQueryWrapper<com.panjia.received.domain.ReceivedContract>()
                    .eq(com.panjia.received.domain.ReceivedContract::getPeriod, period)
                    .eq(com.panjia.received.domain.ReceivedContract::getContractNo, cn));
            if (shells.size() == 1) {
                com.panjia.received.domain.ReceivedContract only = shells.get(0);
                boolean realShell = !SOURCE_TYPE_MANUAL.equals(only.getSourceType())
                    || !cn.equals(StringUtils.trimToNull(only.getOrderNo()));
                if (realShell) {
                    return only;
                }
            }
        }
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
