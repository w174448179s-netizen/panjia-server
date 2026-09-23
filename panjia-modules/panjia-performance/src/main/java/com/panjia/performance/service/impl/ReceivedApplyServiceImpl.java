package com.panjia.performance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.common.util.DeptScopeUtils;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.ReceivedApply;
import com.panjia.performance.domain.ReceivedApplyStatus;
import com.panjia.performance.domain.vo.BatchApproveResultVo;
import com.panjia.performance.dto.BatchFactBindRow;
import com.panjia.performance.domain.bo.ReceivedApplyBo;
import com.panjia.performance.dto.ReceivedContractGroupDTO;
import com.panjia.performance.domain.vo.ReceivedContractMetricsVo;
import com.panjia.performance.domain.vo.ReceivedFactDetailVo;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.mapper.ReceivedApplyMapper;
import com.panjia.performance.service.FactConversionResolver;
import com.panjia.performance.service.IReceivedApplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.port.ApprovalAction;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ApprovalStartCmd;
import com.panjia.contracts.port.ConversionFactorPort;
import com.panjia.contracts.port.MyTaskBrief;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.ConfigService;
import org.dromara.system.api.DeptService;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
public class ReceivedApplyServiceImpl implements IReceivedApplyService {

    private static final String NODE_FINANCE = "rcv_finance";
    private static final String NODE_DIRECTOR = "rcv_director";
    private static final String FACT_TYPE_REAL = FactType.PERF_REAL.getCode();
    private static final String FACT_TYPE_EXPECT = FactType.PERF_EXPECT.getCode();

    private static final String CONFIG_SKIP_FINANCE = "panjia.flow.skip_finance";
    private static final DateTimeFormatter APPLY_NO_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final ReceivedApplyMapper applyMapper;
    private final PerformanceFactMapper factMapper;
    private final ApprovalPort approvalPort;
    private final ConfigService configService;
    private final TaskExecutor taskExecutor;
    /** 折算因子公共方法（取比例 / 金额乘算的唯一入口） */
    private final ConversionFactorPort conversionFactorPort;
    /** 业绩域自有标识 → bizType 的解析（factId 反查） */
    private final FactConversionResolver factConversionResolver;
    /** 部门子树解析（店长/总监数据权限范围） */
    private final DeptService deptService;

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
        List<ReceivedContractGroupDTO> groups = factMapper.selectBatchReceivedContractGroups(batchId, period);
        if (groups.isEmpty()) {
            log.info("[实收审批] 批次无待建单实收订单组：batchId={}, period={}", batchId, period);
            return 0;
        }
        // ===== 批量预加载（3 次 SQL） =====
        // 业务键 = 订单号（聚合维度）
        List<String> bizKeys = groups.stream().map(ReceivedContractGroupDTO::getOrderNo).toList();
        // ① 一条 IN 查询取全部订单的活跃审批单（DRAFT/SUBMITTED/APPROVED）
        Map<String, ReceivedApply> activeApplies = loadActiveAppliesBatch(period, bizKeys);
        // ② 一次查询批次内全部待绑定非零实收事实行，按订单号直接分组（每条事实唯一归属一个订单号）
        Map<String, List<BatchFactBindRow>> rowsByKey = new LinkedHashMap<>();
        for (BatchFactBindRow row : factMapper.selectBatchUnboundRealFacts(batchId, period, bizKeys)) {
            rowsByKey.computeIfAbsent(row.getBizKey(), k -> new ArrayList<>()).add(row);
        }
        // ③ 一次批量聚合全部业务键的应收合计（PERF_EXPECT）
        Map<String, BigDecimal> expectedMap = factMapper.selectExpectSumsByBizKeys(period, bizKeys)
            .stream()
            .collect(Collectors.toMap(BatchFactBindRow::getBizKey,
                r -> r.getAmount() == null ? BigDecimal.ZERO : r.getAmount(),
                (a, b) -> a));

        int created = 0;
        for (ReceivedContractGroupDTO group : groups) {
            String bizKey = group.getOrderNo();
            List<BatchFactBindRow> rows = rowsByKey.getOrDefault(bizKey, List.of());
            if (rows.isEmpty()) {
                continue;
            }
            List<Long> factIds = rows.stream().map(BatchFactBindRow::getFactId).toList();
            BigDecimal realSum = rows.stream()
                .map(r -> r.getAmount() == null ? BigDecimal.ZERO : r.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            Long uniqueDeptId = uniqueDeptId(rows);
            BigDecimal expectedAmount = expectedMap.getOrDefault(bizKey, BigDecimal.ZERO);
            try {
                ReceivedApply existing = activeApplies.get(bizKey);
                if (existing == null) {
                    // ===== 新建：insert 前金额/门店已在内存算好，startWorkflow 的 updateById 一并落库 =====
                    ReceivedApply apply = newApplyFromGroup(period, group, batchId, operatorId,
                        rows.size(), realSum, expectedAmount, uniqueDeptId);
                    insertApply(apply);
                    bindFacts(factIds, apply.getId());
                    startWorkflow(apply, apply.getApplicantId(), Set.of());
                    created++;
                    log.info("[实收审批] 导入自动建单并提交：applyNo={}, orderNo={}, received={}",
                        apply.getApplyNo(), apply.getOrderNo(), apply.getReceivedAmount());
                } else if (existing.getStatus() == ReceivedApplyStatus.APPROVED) {
                    // ★ 已审批通过单不再合并新事实：避免绕过审批流程改变已审批金额
                    log.info("[实收审批] 订单本月审批单已 APPROVED，跳过新批次事实合并：applyId={}, orderNo={}",
                        existing.getId(), bizKey);
                } else {
                    // ===== 合并 DRAFT/SUBMITTED：绑定新事实后在内存累加合计，无需重查已绑定事实 =====
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
                        existing.setBizType(group.getBizType());
                    }
                    applyMapper.updateById(existing);
                    log.info("[实收审批] 新批次实收事实合并入既有审批单：applyId={}, orderNo={}, status={}",
                        existing.getId(), bizKey, existing.getStatus());
                }
            } catch (DuplicateKeyException e) {
                log.warn("[实收审批] 并发建单撞唯一索引，跳过：period={}, orderNo={}", period, bizKey);
            }
        }
        log.info("[实收审批] 批次自动建单完成：batchId={}, period={}, 新建={}", batchId, period, created);
        return created;
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

    /** 事实行归属门店去重：恰好一个门店时返回该 ID，多门店/无门店返回 null（跨店合作单留空）。 */
    private Long uniqueDeptId(List<BatchFactBindRow> rows) {
        Set<Long> deptIds = new java.util.HashSet<>();
        for (BatchFactBindRow row : rows) {
            if (row.getDeptId() != null) {
                deptIds.add(row.getDeptId());
            }
        }
        return deptIds.size() == 1 ? deptIds.iterator().next() : null;
    }

    // ==================== 手工提交（§2.2 发起人路由） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReceivedApply manualSubmit(String period, String contractNo) {
        if (StringUtils.isBlank(period) || StringUtils.isBlank(contractNo)) {
            throw new ServiceException("结算月与合同号不能为空");
        }
        Long operatorId = LoginHelper.getUserId();
        Set<String> roles = currentRoles();

        ReceivedApply apply = findActiveApply(period, contractNo);
        if (apply == null) {
            apply = newApplyFromContractFacts(period, contractNo, operatorId);
            if (apply.getItemCount() == 0) {
                throw new ServiceException("合同 " + contractNo + " " + period + " 月无可提交的实收业绩");
            }
            insertApply(apply);
            bindAllContractFacts(apply);
            refreshTotals(apply);
            applyMapper.updateById(apply);
        } else if (apply.getStatus() == ReceivedApplyStatus.APPROVED) {
            throw new ServiceException("合同 " + contractNo + " 实收业绩已审批通过，如需追加请走调整流程或解封后重发");
        } else if (apply.getStatus() == ReceivedApplyStatus.SUBMITTED) {
            throw new ServiceException("合同 " + contractNo + " 实收业绩审批中，请勿重复提交");
        }

        apply.setApplicantId(operatorId);
        applyMapper.updateById(apply);
        startWorkflow(apply, operatorId, roles);
        return apply;
    }

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
     */
    private void unbindFacts(Long applyId) {
        factMapper.update(null, new LambdaUpdateWrapper<PerformanceFact>()
            .eq(PerformanceFact::getReceivedApplyId, applyId)
            .set(PerformanceFact::getReceivedApplyId, null));
        log.info("[实收审批] 已解绑审批单事实：applyId={}", applyId);
    }

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
     */
    private BatchApproveResultVo doBatchApprove(List<RcvApproveItem> items,
                                                  Long operatorId, String operatorName) {
        BatchApproveResultVo result = new BatchApproveResultVo();
        result.setTotal(items.size());
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
        result.setSuccess(result.getSuccessContracts().size());
        result.setSkipped(result.getSkippedContracts().size());
        result.setFailed(result.getFailedContracts().size());
        log.info("[实收审批] 批量审批完成：成功={}, 跳过={}, 失败={}",
            result.getSuccess(), result.getSkipped(), result.getFailed());
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
            .orderByDesc(ReceivedApply::getCreateTime);
        // 审批节点数据隔离：审批中单据只允许本人角色对应节点可见（前端不再传节点参数，防绕过由服务端强制）
        applyApprovalNodeScope(wrapper);
        // 门店/组别筛选：通过事实表过滤（合同下人员可能跨部门，不能用审批单的单一 dept_id）。
        // 只要有一笔事实属于本部门（含下级），该审批单就可见。
        if (effectiveDeptId != null) {
            wrapper.and(w -> w.apply(
                "EXISTS (SELECT 1 FROM pj_perf_fact f"
                    + " WHERE f.received_apply_id = pj_perf_received_apply.id"
                    + " AND f.fact_status = 'ACTIVE'"
                    + " AND (f.dept_id = {0}"
                    + " OR f.dept_id IN (SELECT sd.dept_id FROM sys_dept sd"
                    + " WHERE sd.ancestors LIKE CONCAT('%', {0}, '%'))))",
                effectiveDeptId));
        }
        // 员工筛选：通过事实表的 employee_id 过滤（合同下可能有多个员工）
        if (query.getEmployeeId() != null) {
            wrapper.and(w -> w.apply(
                "EXISTS (SELECT 1 FROM pj_perf_fact f"
                    + " WHERE f.received_apply_id = pj_perf_received_apply.id"
                    + " AND f.fact_status = 'ACTIVE'"
                    + " AND f.employee_id = {0})",
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
                // 折算后金额：应收合计与实收合计用同一因子，从批量结果中取（Map 查询，无 DB 访问）
                BigDecimal factor = conversionFactorPort.factorOf(factorMap, bizType);
                if (m.getExpectedAmount() != null) {
                    apply.setExpectedConvertedAmount(conversionFactorPort.convert(m.getExpectedAmount(), factor));
                    if (apply.getOriginalExpectedAmount() != null) {
                        apply.setOriginalExpectedConvertedAmount(
                            conversionFactorPort.convert(apply.getOriginalExpectedAmount(), factor));
                    }
                }
                if (apply.getReceivedAmount() != null) {
                    apply.setReceivedConvertedAmount(conversionFactorPort.convert(apply.getReceivedAmount(), factor));
                    if (apply.getOriginalReceivedAmount() != null) {
                        apply.setOriginalReceivedConvertedAmount(
                            conversionFactorPort.convert(apply.getOriginalReceivedAmount(), factor));
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
        // 折算后金额：按 factId 批量解析因子，应收业绩与实收业绩同取本行因子，
        // 取比例与乘算都走公共方法（ConversionFactorPort）
        Set<Long> factIds = facts.stream().map(ReceivedFactDetailVo::getFactId).filter(f -> f != null).collect(java.util.stream.Collectors.toSet());
        Map<Long, BigDecimal> factorMap = factConversionResolver.factorByFactIds(factIds);
        BigDecimal recvSum = BigDecimal.ZERO;
        BigDecimal recvOriginalSum = BigDecimal.ZERO;
        BigDecimal recvConvertedSum = BigDecimal.ZERO;
        BigDecimal recvOriginalConvertedSum = BigDecimal.ZERO;
        BigDecimal expectConvertedSum = BigDecimal.ZERO;
        BigDecimal expectOriginalConvertedSum = BigDecimal.ZERO;
        boolean receivedAdjusted = false;
        for (ReceivedFactDetailVo f : facts) {
            BigDecimal factor = conversionFactorPort.factorOf(factorMap, f.getFactId());
            f.setConvertedAmount(conversionFactorPort.convert(f.getAmount(), factor));
            f.setExpectedConvertedAmount(conversionFactorPort.convert(f.getExpectedAmount(), factor));
            // 调整前应收的折算后金额：与当前值同一因子，仅在原值存在时输出（无调整则与原值一致，前端不展示）
            if (f.getOriginalExpectedAmount() != null) {
                f.setOriginalConvertedAmount(conversionFactorPort.convert(f.getOriginalExpectedAmount(), factor));
            }
            // 调整前实收的折算后金额：结佣调整 supersede 后同 sourceKey 存在 REVERSED 的 PERF_REAL
            if (f.getOriginalAmount() != null) {
                f.setOriginalReceivedConvertedAmount(conversionFactorPort.convert(f.getOriginalAmount(), factor));
            }
            if (Boolean.TRUE.equals(f.getReceivedAdjusted())) {
                receivedAdjusted = true;
            }
            if (f.getAmount() != null) recvSum = recvSum.add(f.getAmount());
            if (f.getOriginalAmount() != null) recvOriginalSum = recvOriginalSum.add(f.getOriginalAmount());
            if (f.getConvertedAmount() != null) recvConvertedSum = recvConvertedSum.add(f.getConvertedAmount());
            if (f.getOriginalReceivedConvertedAmount() != null) {
                recvOriginalConvertedSum = recvOriginalConvertedSum.add(f.getOriginalReceivedConvertedAmount());
            }
            if (f.getExpectedConvertedAmount() != null) expectConvertedSum = expectConvertedSum.add(f.getExpectedConvertedAmount());
            if (f.getOriginalConvertedAmount() != null) expectOriginalConvertedSum = expectOriginalConvertedSum.add(f.getOriginalConvertedAmount());
        }
        // 实收合计与明细列同源（实时 ACTIVE PERF_REAL 求和，含已生效结佣调整）；
        // 存在已调整行时留存「调整前合计」，供详情「实收合计」展示「原值 → 调整后值」
        apply.setReceivedAmount(recvSum);
        apply.setReceivedAdjusted(receivedAdjusted);
        if (receivedAdjusted) {
            apply.setOriginalReceivedAmount(recvOriginalSum);
            apply.setOriginalReceivedConvertedAmount(recvOriginalConvertedSum);
        }
        apply.setReceivedConvertedAmount(recvConvertedSum);
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

    /**
     * 自动建单组装。实收合计/条数/门店由批量预加载的非零事实行内存计算，
     * 不再在 insert 后重查事实（注意：聚合 group 的 itemCount 含 0 值行，
     * 与实际绑定的非零行数不一致，最终落库口径以 factCount 为准）。
     */
    private ReceivedApply newApplyFromGroup(String period, ReceivedContractGroupDTO group,
                                            Long batchId, Long applicantId,
                                            int factCount, BigDecimal receivedAmount,
                                            BigDecimal expectedAmount, Long deptId) {
        ReceivedApply apply = baseApply(period, group.getContractNo(), applicantId);
        apply.setOrderNo(group.getOrderNo());
        apply.setBizType(group.getBizType());
        apply.setPropertyAddress(group.getPropertyAddress());
        apply.setBusinessDate(group.getBusinessDate());
        apply.setBatchId(batchId);
        apply.setReceivedAmount(receivedAmount);
        apply.setExpectedAmount(expectedAmount);
        apply.setItemCount(factCount);
        apply.setDeptId(deptId);
        return apply;
    }

    /** 手工建单：取合同全部非零 ACTIVE 实收事实。 */
    private ReceivedApply newApplyFromContractFacts(String period, String contractNo, Long applicantId) {
        List<PerformanceFact> realFacts = factMapper.selectActiveFactsByContractNo(
            period, FACT_TYPE_REAL, contractNo);
        List<PerformanceFact> nonZero = realFacts.stream()
            .filter(f -> f.getPerformanceAmount() != null
                && f.getPerformanceAmount().compareTo(BigDecimal.ZERO) != 0)
            .toList();
        ReceivedApply apply = baseApply(period, contractNo, applicantId);
        if (!nonZero.isEmpty()) {
            PerformanceFact first = nonZero.get(0);
            // 业务类型快照（落库列 biz_type）：手工建单也要带上，供列表展示/筛选
            apply.setBizType(first.getBizType());
            // 快照字段由事实 JOIN 取出的摘要回填
            List<PerformanceFactSummaryDTO> summaries = factMapper
                .selectActiveFactSummariesByContractNo(period, FACT_TYPE_REAL, contractNo);
            PerformanceFactSummaryDTO snap = summaries.isEmpty() ? null : summaries.get(0);
            if (snap != null) {
                apply.setOrderNo(snap.getOrderNo());
                apply.setPropertyAddress(snap.getPropertyAddress());
            }
            apply.setBusinessDate(first.getBusinessDate() == null ? null
                : first.getBusinessDate().atStartOfDay());
            apply.setBatchId(first.getBatchId());
            Set<Long> deptIds = new java.util.HashSet<>();
            for (PerformanceFact f : nonZero) {
                if (f.getDeptId() != null) {
                    deptIds.add(f.getDeptId());
                }
            }
            apply.setDeptId(deptIds.size() == 1 ? first.getDeptId() : null);
            apply.setItemCount(nonZero.size());
            apply.setReceivedAmount(nonZero.stream()
                .map(PerformanceFact::getPerformanceAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
            apply.setExpectedAmount(sumExpect(period, contractNo));
        } else {
            apply.setItemCount(0);
            apply.setReceivedAmount(BigDecimal.ZERO);
            apply.setExpectedAmount(sumExpect(period, contractNo));
        }
        return apply;
    }

    private ReceivedApply baseApply(String period, String contractNo, Long applicantId) {
        ReceivedApply apply = new ReceivedApply();
        apply.setApplyNo("RCV" + LocalDateTime.now().format(APPLY_NO_FORMATTER));
        apply.setPeriod(period);
        apply.setContractNo(contractNo);
        apply.setStatus(ReceivedApplyStatus.DRAFT);
        apply.setApplicantId(applicantId);
        return apply;
    }

    private void insertApply(ReceivedApply apply) {
        try {
            applyMapper.insert(apply);
        } catch (DuplicateKeyException e) {
            throw new ServiceException("合同 " + apply.getContractNo() + " " + apply.getPeriod()
                + " 月已存在未完结实收审批单，请刷新");
        }
    }

    /** 绑定合同下全部未挂单的非零实收事实（手工建单）。 */
    private void bindAllContractFacts(ReceivedApply apply) {
        List<PerformanceFact> facts = factMapper.selectActiveFactsByContractNo(
            apply.getPeriod(), FACT_TYPE_REAL, apply.getContractNo());
        List<Long> ids = facts.stream()
            .filter(f -> f.getReceivedApplyId() == null
                && f.getPerformanceAmount() != null
                && f.getPerformanceAmount().compareTo(BigDecimal.ZERO) != 0)
            .map(PerformanceFact::getId)
            .toList();
        bindFacts(ids, apply.getId());
    }

    private void bindFacts(List<Long> factIds, Long applyId) {
        if (factIds.isEmpty()) {
            return;
        }
        factMapper.update(null, new LambdaUpdateWrapper<PerformanceFact>()
            .in(PerformanceFact::getId, factIds)
            .set(PerformanceFact::getReceivedApplyId, applyId));
    }

    /** 按已绑定事实重算实收合计/条数，并刷新应收合计与快照。 */
    private void refreshTotals(ReceivedApply apply) {
        List<PerformanceFact> bound = factMapper.selectList(new LambdaQueryWrapper<PerformanceFact>()
            .eq(PerformanceFact::getReceivedApplyId, apply.getId())
            .eq(PerformanceFact::getFactStatus, com.panjia.performance.domain.FactStatus.ACTIVE));
        apply.setItemCount(bound.size());
        apply.setReceivedAmount(bound.stream()
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add));
        apply.setExpectedAmount(sumExpect(apply.getPeriod(), apply.getContractNo()));
        if (bound.size() == 1 && apply.getDeptId() == null) {
            apply.setDeptId(bound.get(0).getDeptId());
        }
    }

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
}
