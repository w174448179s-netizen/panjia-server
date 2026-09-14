package com.panjia.performance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.ReceivedApply;
import com.panjia.performance.domain.ReceivedApplyStatus;
import com.panjia.performance.dto.ReceivedApplyQuery;
import com.panjia.performance.dto.ReceivedBatchApproveResult;
import com.panjia.performance.dto.ReceivedContractGroupDTO;
import com.panjia.performance.dto.ReceivedContractMetricsDTO;
import com.panjia.performance.dto.ReceivedFactDetailDTO;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.mapper.ReceivedApplyMapper;
import com.panjia.performance.service.ReceivedApplyService;
import com.panjia.performance.util.ExcelContractAmountParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.ConfigService;
import org.dromara.workflow.api.WorkflowService;
import org.dromara.workflow.api.domain.CompleteTaskDTO;
import org.dromara.workflow.api.domain.FlowInstanceBizExtDTO;
import org.dromara.workflow.api.domain.StartProcessDTO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
public class ReceivedApplyServiceImpl implements ReceivedApplyService {

    private static final String FLOW_CODE = "perf_received";
    private static final String NODE_FINANCE = "rcv_finance";
    private static final String NODE_DIRECTOR = "rcv_director";
    /** 业务角色标识，与 flow_node.permission_flag 的 role:…012 / role:…010 对应。 */
    private static final String ROLE_FINANCE = "finance";
    private static final String ROLE_DIRECTOR = "director";
    private static final String FACT_TYPE_REAL = FactType.PERF_REAL.getCode();
    private static final String FACT_TYPE_EXPECT = FactType.PERF_EXPECT.getCode();

    private static final String CONFIG_SKIP_FINANCE = "panjia.flow.skip_finance";
    private static final DateTimeFormatter APPLY_NO_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final ReceivedApplyMapper applyMapper;
    private final PerformanceFactMapper factMapper;
    private final WorkflowService workflowService;
    private final ConfigService configService;

    // ==================== 导入自动建单（§2.1） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int autoCreateForBatch(Long batchId, String period) {
        if (batchId == null || StringUtils.isBlank(period)) {
            return 0;
        }
        List<ReceivedContractGroupDTO> groups = factMapper.selectBatchReceivedContractGroups(batchId, period);
        if (groups.isEmpty()) {
            log.info("[实收审批] 批次无待建单实收合同组：batchId={}, period={}", batchId, period);
            return 0;
        }
        int created = 0;
        for (ReceivedContractGroupDTO group : groups) {
            try {
                ReceivedApply existing = findActiveApply(period, group.getContractNo());
                if (existing == null) {
                    ReceivedApply apply = newApplyFromGroup(period, group, batchId, null);
                    insertApply(apply);
                    bindBatchFacts(apply, batchId);
                    refreshTotals(apply);
                    applyMapper.updateById(apply);
                    startWorkflow(apply, null, Set.of());
                    created++;
                    log.info("[实收审批] 导入自动建单并提交：applyNo={}, contractNo={}, received={}",
                        apply.getApplyNo(), apply.getContractNo(), apply.getReceivedAmount());
                } else {
                    // 同合同已有未完结单：新批次事实合并进既有单（审批单以合同为粒度，事实随到随并）
                    bindBatchFacts(existing, batchId);
                    refreshTotals(existing);
                    applyMapper.updateById(existing);
                    log.info("[实收审批] 新批次实收事实合并入既有审批单：applyId={}, contractNo={}, status={}",
                        existing.getId(), group.getContractNo(), existing.getStatus());
                }
            } catch (DuplicateKeyException e) {
                log.warn("[实收审批] 并发建单撞唯一索引，跳过：period={}, contractNo={}",
                    period, group.getContractNo());
            }
        }
        log.info("[实收审批] 批次自动建单完成：batchId={}, period={}, 新建={}", batchId, period, created);
        return created;
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
            throw new ServiceException("合同 " + contractNo + " 实收业绩已审批通过");
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
        Long taskId = workflowService.getCurrentTaskId(String.valueOf(id));
        if (taskId == null) {
            throw new ServiceException("审批流程任务不存在，请联系管理员");
        }
        workflowService.completeTask(taskId, "重新提交");
        routeAfterApplicant(apply, currentRoles());
        return apply;
    }

    // ==================== 审批 / 驳回 / 作废 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, String message) {
        ReceivedApply apply = getAndCheck(id);
        if (apply.getStatus() != ReceivedApplyStatus.SUBMITTED) {
            throw new ServiceException("仅审批中的单据可审批（当前：" + apply.getStatus().getDesc() + "）");
        }
        Long taskId = workflowService.getCurrentTaskId(String.valueOf(id));
        if (taskId == null) {
            throw new ServiceException("当前无待办任务");
        }
        // 以当前登录人身份办理：不设置 ignore，由流程引擎按 flow_user 中的本节点办理人判权。
        // 财务在总监节点、或任何非本节点办理人调用，都会被引擎拒绝（不能再用 ignore 绕过）。
        CompleteTaskDTO completeTask = new CompleteTaskDTO();
        completeTask.setTaskId(taskId);
        completeTask.setMessage(StringUtils.isBlank(message) ? "审批通过" : message);
        completeTaskAsLoginUser(completeTask);
        refreshCurrentNode(apply);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String message) {
        ReceivedApply apply = getAndCheck(id);
        if (apply.getStatus() != ReceivedApplyStatus.SUBMITTED) {
            throw new ServiceException("仅审批中的单据可驳回（当前：" + apply.getStatus().getDesc() + "）");
        }
        // 驳回走的是门面提供的系统身份方法（内部 ignore=true，引擎不鉴权），
        // 故在业务层补一道「登录人角色 = 当前节点办理角色」校验，堵住财务驳回总监节点单据的越权。
        assertCurrentNodeHandler(apply);
        Long taskId = workflowService.getCurrentTaskId(String.valueOf(id));
        if (taskId == null) {
            throw new ServiceException("当前无待办任务");
        }
        workflowService.rejectTask(taskId, StringUtils.isBlank(message) ? "驳回" : message);
        // back 事件由监听器置 REJECTED；同步刷新本实例状态
        apply.setStatus(ReceivedApplyStatus.REJECTED);
        apply.setCurrentNode(null);
        applyMapper.updateById(apply);
    }

    /**
     * 以当前登录人身份办理任务（不忽略权限）。
     * <p>越权时流程引擎抛 {@code NULL_ROLE_NODE}（"无法跳转到该节点,请检查当前用户是否有权限!"），
     * 此处转为业务可读提示；其余异常原样抛出，避免掩盖真实故障。</p>
     */
    private void completeTaskAsLoginUser(CompleteTaskDTO completeTask) {
        // 平台约定：超管等同系统身份（原生 TaskOpPrepareComponent 亦对超管置 ignore），
        // 保留其运维解卡能力；除此之外的所有业务角色一律走引擎原生鉴权。
        if (LoginHelper.isSuperAdmin()) {
            workflowService.completeTask(completeTask.getTaskId(), completeTask.getMessage());
            return;
        }
        try {
            workflowService.completeTask(completeTask);
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("请检查当前用户是否有权限") || msg.contains("无法跳转到该节点")) {
                throw new ServiceException("您不是该单据当前审批节点的办理人，无权审批", e);
            }
            throw e;
        }
    }

    /**
     * 校验登录人是否为本单据当前节点对应的办理角色。
     * <p>节点 → 角色的映射与流程定义 {@code flow_node.permission_flag}
     * （rcv_finance → role:…012 财务、rcv_director → role:…010 总监）保持一致。</p>
     */
    private void assertCurrentNodeHandler(ReceivedApply apply) {
        String nodeCode = workflowService.getCurrentNodeCode(String.valueOf(apply.getId()));
        String requiredRole;
        if (NODE_DIRECTOR.equals(nodeCode)) {
            requiredRole = ROLE_DIRECTOR;
        } else if (NODE_FINANCE.equals(nodeCode)) {
            requiredRole = ROLE_FINANCE;
        } else {
            throw new ServiceException("该单据当前不在可审批节点，无法驳回");
        }
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        if (!currentRoles().contains(requiredRole)) {
            throw new ServiceException("该单据当前由「"
                + (ROLE_DIRECTOR.equals(requiredRole) ? "总监" : "财务") + "」办理，您无权驳回");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id) {
        ReceivedApply apply = getAndCheck(id);
        if (apply.getStatus() != ReceivedApplyStatus.DRAFT && apply.getStatus() != ReceivedApplyStatus.SUBMITTED) {
            throw new ServiceException("仅待提交/审批中的单据可作废（当前：" + apply.getStatus().getDesc() + "）");
        }
        if (StringUtils.isNotBlank(apply.getProcessInstanceId())) {
            // 终止运行中的流程实例（触发 cancel 事件，监听器幂等置 CANCELLED）
            workflowService.deleteInstance(List.of(String.valueOf(id)));
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

    // ==================== Excel 批量审批（§2.3） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReceivedBatchApproveResult batchApprove(String period, MultipartFile file) {
        if (StringUtils.isBlank(period)) {
            throw new ServiceException("结算月不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new ServiceException("请上传 Excel 文件（.xlsx/.xls）");
        }
        List<ExcelContractAmountParser.ContractAmountRow> rows;
        try {
            rows = ExcelContractAmountParser.parse(file.getInputStream());
        } catch (Exception e) {
            throw new ServiceException("Excel 读取失败：{}", e.getMessage());
        }

        ReceivedBatchApproveResult result = new ReceivedBatchApproveResult();
        for (ExcelContractAmountParser.ContractAmountRow row : rows) {
            try {
                BigDecimal amount = ExcelContractAmountParser.parseAmount(row.getAmountText());
                ReceivedApply apply = applyMapper.selectOne(new LambdaQueryWrapper<ReceivedApply>()
                    .eq(ReceivedApply::getPeriod, period)
                    .eq(ReceivedApply::getContractNo, row.getContractNo())
                    .eq(ReceivedApply::getStatus, ReceivedApplyStatus.SUBMITTED)
                    .orderByDesc(ReceivedApply::getId)
                    .last("LIMIT 1"));
                if (apply == null) {
                    result.addFailure(row.getContractNo(), row.getAmountText(), "无审批中的实收审批单");
                    continue;
                }
                if (amount == null) {
                    result.addFailure(row.getContractNo(), row.getAmountText(), "金额无法识别");
                    continue;
                }
                if (apply.getReceivedAmount() == null
                    || apply.getReceivedAmount().compareTo(amount) != 0) {
                    result.addFailure(row.getContractNo(), row.getAmountText(),
                        "金额不匹配，单据实收=" + apply.getReceivedAmount());
                    continue;
                }
                Long taskId = workflowService.getCurrentTaskId(String.valueOf(apply.getId()));
                if (taskId == null) {
                    result.addFailure(row.getContractNo(), row.getAmountText(), "当前无待办任务");
                    continue;
                }
                // 复用单张审批：与「我的待办 → 去处理」同一条鉴权路径，由引擎按 flow_user 名单判权。
                // 越权行（如财务对停在总监节点的单据）抛 ServiceException，被上方 catch 记为该行失败并透出原因，不中断整批。
                // 此前直接 completeTask(taskId, ...)（内部 ignore=true）会让任何持有本接口权限的角色批量批掉他人节点的单据。
                approve(apply.getId(), "Excel 批量审批通过");
                result.addSuccess();
            } catch (Exception e) {
                result.addFailure(row.getContractNo(), row.getAmountText(), e.getMessage());
            }
        }
        log.info("[实收审批] Excel 批量审批完成：period={}, 成功={}, 失败={}",
            period, result.getSuccessCount(), result.getFailedRows().size());
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
                applyMapper.updateById(apply);
                log.info("[实收审批工作流] 驳回：applyId={}, message={}", applyId, message);
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

    // ==================== 查询 ====================

    @Override
    public PageResult<ReceivedApply> list(ReceivedApplyQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<ReceivedApply> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.isNotBlank(query.getPeriod()), ReceivedApply::getPeriod, query.getPeriod())
            .eq(query.getBatchId() != null, ReceivedApply::getBatchId, query.getBatchId())
            .eq(StringUtils.isNotBlank(query.getCurrentNode()),
                ReceivedApply::getCurrentNode, query.getCurrentNode())
            .eq(StringUtils.isNotBlank(query.getStatus()),
                ReceivedApply::getStatus, ReceivedApplyStatus.fromCode(query.getStatus()))
            .and(StringUtils.isNotBlank(query.getKeyword()), w -> w
                .like(ReceivedApply::getContractNo, query.getKeyword())
                .or().like(ReceivedApply::getOrderNo, query.getKeyword())
                .or().like(ReceivedApply::getPropertyAddress, query.getKeyword()))
            .orderByDesc(ReceivedApply::getCreateTime);
        Page<ReceivedApply> page = applyMapper.selectPage(pageQuery.build(), wrapper);
        List<ReceivedApply> records = page.getRecords();
        fillContractMetrics(records);
        return PageResult.build(records, page.getTotal());
    }

    /**
     * 回填实收明细列表的补充字段（业务类型、涉及人数）。
     * <p>
     * 审批单表不存这两个字段，按 (period, contractNo) 从 ACTIVE PERF_REAL 事实聚合，
     * 口径与详情弹窗「每人实收明细」一致；按期间分组批量查询，避免 N+1。
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
        Map<String, ReceivedContractMetricsDTO> metrics = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : contractsByPeriod.entrySet()) {
            List<ReceivedContractMetricsDTO> rows =
                factMapper.selectReceivedContractMetrics(entry.getKey(), entry.getValue());
            for (ReceivedContractMetricsDTO row : rows) {
                metrics.put(metricsKey(entry.getKey(), row.getContractNo()), row);
            }
        }
        for (ReceivedApply apply : records) {
            ReceivedContractMetricsDTO m = metrics.get(metricsKey(apply.getPeriod(), apply.getContractNo()));
            if (m != null) {
                apply.setBizType(m.getBizType());
                apply.setEmployeeCount(m.getEmployeeCount());
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
        List<ReceivedFactDetailDTO> facts = factMapper.selectReceivedFactDetails(
            apply.getPeriod(), apply.getContractNo());
        return new ReceivedApplyDetail(apply, facts);
    }

    // ==================== 内部方法 ====================

    /**
     * 构建流程业务扩展信息，供「我的待办 / 我发起的」列表直接展示"在审什么"。
     */
    private FlowInstanceBizExtDTO buildBizExt(ReceivedApply apply) {
        FlowInstanceBizExtDTO bizExt = new FlowInstanceBizExtDTO();
        bizExt.setBusinessId(String.valueOf(apply.getId()));
        bizExt.setBusinessCode(text(apply.getApplyNo()));
        bizExt.setBusinessTitle("实收审批｜" + text(apply.getContractNo())
            + " " + text(apply.getPropertyAddress())
            + "｜账期" + text(apply.getPeriod())
            + "｜实收" + text(apply.getReceivedAmount()));
        return bizExt;
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

        StartProcessDTO start = new StartProcessDTO();
        start.setBusinessId(String.valueOf(apply.getId()));
        start.setFlowCode(FLOW_CODE);
        Map<String, Object> variables = new HashMap<>(2);
        variables.put("ignore", true);
        start.setVariables(variables);
        start.setBizExt(buildBizExt(apply));
        try {
            boolean ok = workflowService.startCompleteTask(start);
            if (!ok) {
                throw new ServiceException("实收审批流程发起失败");
            }
        } catch (Exception e) {
            log.error("[实收审批] 流程发起异常：applyId={}", apply.getId(), e);
            throw new ServiceException("实收审批流程发起失败：{}", e.getMessage());
        }
        Long instanceId = workflowService.getInstanceIdByBusinessId(String.valueOf(apply.getId()));
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
                workflowService.completeTask(financeTask,
                    director ? "总监发起，系统自动流转" : "财务发起，系统自动流转");
            }
        }
        // 总监发起：总监节点系统自动办理 → 流程完成（finish 事件置 APPROVED）
        if (director) {
            Long directorTask = taskAtNode(apply.getId(), NODE_DIRECTOR);
            if (directorTask != null) {
                workflowService.completeTask(directorTask, "总监发起，系统自动审批通过");
            }
        }
        refreshCurrentNode(apply);
    }

    private Long taskAtNode(Long applyId, String nodeCode) {
        String current = workflowService.getCurrentNodeCode(String.valueOf(applyId));
        return nodeCode.equals(current) ? workflowService.getCurrentTaskId(String.valueOf(applyId)) : null;
    }

    /** 从工作流回写当前节点（rcv_finance→FINANCE / rcv_director→DIRECTOR / 已结束→null）。 */
    private void refreshCurrentNode(ReceivedApply apply) {
        String nodeCode = workflowService.getCurrentNodeCode(String.valueOf(apply.getId()));
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

    private ReceivedApply newApplyFromGroup(String period, ReceivedContractGroupDTO group,
                                            Long batchId, Long applicantId) {
        ReceivedApply apply = baseApply(period, group.getContractNo(), applicantId);
        apply.setOrderNo(group.getOrderNo());
        apply.setPropertyAddress(group.getPropertyAddress());
        apply.setBusinessDate(group.getBusinessDate());
        apply.setBatchId(batchId);
        apply.setReceivedAmount(group.getReceivedAmount());
        apply.setExpectedAmount(group.getExpectedAmount());
        apply.setItemCount(group.getItemCount());
        apply.setDeptId(resolveDeptId(period, group.getContractNo(), batchId));
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

    /** 绑定批次内、合同下尚未挂单的实收事实。 */
    private void bindBatchFacts(ReceivedApply apply, Long batchId) {
        List<PerformanceFact> facts = factMapper.selectActiveFactsByContractNo(
            apply.getPeriod(), FACT_TYPE_REAL, apply.getContractNo());
        List<Long> ids = facts.stream()
            .filter(f -> batchId.equals(f.getBatchId()) && f.getReceivedApplyId() == null
                && f.getPerformanceAmount() != null
                && f.getPerformanceAmount().compareTo(BigDecimal.ZERO) != 0)
            .map(PerformanceFact::getId)
            .toList();
        bindFacts(ids, apply.getId());
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

    /** 自动建单门店快照：取该合同本批次事实的唯一门店（多门店为空）。 */
    private Long resolveDeptId(String period, String contractNo, Long batchId) {
        List<PerformanceFact> facts = factMapper.selectActiveFactsByContractNo(
            period, FACT_TYPE_REAL, contractNo);
        Set<Long> deptIds = new java.util.HashSet<>();
        for (PerformanceFact f : facts) {
            if (batchId.equals(f.getBatchId()) && f.getDeptId() != null) {
                deptIds.add(f.getDeptId());
            }
        }
        return deptIds.size() == 1 ? new ArrayList<>(deptIds).get(0) : null;
    }

    private ReceivedApply findActiveApply(String period, String contractNo) {
        return applyMapper.selectOne(new LambdaQueryWrapper<ReceivedApply>()
            .eq(ReceivedApply::getPeriod, period)
            .eq(ReceivedApply::getContractNo, contractNo)
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
