package com.panjia.commission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.panjia.commission.domain.ApplicationStatus;
import com.panjia.commission.domain.CommissionApplication;
import com.panjia.commission.domain.CommissionConsumeLog;
import com.panjia.commission.domain.CommissionItem;
import com.panjia.commission.domain.ItemStatus;
import com.panjia.commission.domain.ReversedReason;
import com.panjia.commission.dto.ApplyQuery;
import com.panjia.commission.dto.CommissionBatchResult;
import com.panjia.commission.dto.CommissionContractVO;
import com.panjia.commission.mapper.CommissionApplicationMapper;
import com.panjia.commission.mapper.CommissionConsumeLogMapper;
import com.panjia.commission.mapper.CommissionItemMapper;
import com.panjia.commission.util.CommissionBatchExcelParser;
import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.contracts.dto.PerformanceContractSummaryDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.dto.ReceivedAlignmentResultDTO;
import com.panjia.contracts.event.CommissionApprovedEvent;
import com.panjia.contracts.event.EventPort;
import com.panjia.contracts.port.CommissionPerformanceQueryPort;
import com.panjia.contracts.port.EmployeeMainDataQueryPort;
import com.panjia.contracts.port.PeriodCloseQueryPort;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 结佣申请服务（按合同发起 / 提交 / 审批锁定，结佣域详细设计 §4.1~§4.2，新流程 §3）。
 * <p>
 * 申请单粒度 = <b>合同 + 业绩归属月</b>：一个合同当月一张申请单，独立提交、独立审批。
 * <p>
 * 新业务口径：
 * <ul>
 *   <li>仅可对<b>实收审批通过</b>（received_apply APPROVED）的实收业绩发起（§3.2）；</li>
 *   <li>金额为结佣业绩金额（PERF_REAL 原样透传），0 值实收不入单（ADR B14）；</li>
 *   <li>审批流 commission_apply：申请人 → 总监 → 财务；总监发起时系统自动过总监节点（§3.1）；</li>
 *   <li>实收=应收无差异：总监通过后不流转财务（§3.4）；skip_finance=true 全局跳过财务；</li>
 *   <li>有差异：总监通过时系统自动把实收对齐应收（合同+每人明细都改），再流转财务人工审批（§3.5）；</li>
 *   <li>审批通过月 = 工资归属月 approved_month（V4.2 硬要求 1）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionApplicationService {

    private static final DateTimeFormatter PERIOD_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter APPLY_NO_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** 结佣口径：实收业绩（结佣确认对象） */
    private static final String FACT_TYPE_REAL = "PERF_REAL";

    private static final String FLOW_CODE = "commission_apply";
    private static final String NODE_DIRECTOR = "capp_director";
    private static final String NODE_FINANCE = "capp_finance";
    /** 业务角色标识，与 flow_node.permission_flag 的 role:…010 / role:…012 对应。 */
    private static final String ROLE_DIRECTOR = "director";
    private static final String ROLE_FINANCE = "finance";
    private static final String CONFIG_SKIP_FINANCE = "panjia.flow.skip_finance";

    /** 列表行虚拟状态：未发起（业绩存在但无申请单） */
    public static final String ROW_STATUS_NONE = "NONE";

    private final CommissionApplicationMapper applicationMapper;
    private final CommissionItemMapper itemMapper;
    private final CommissionConsumeLogMapper consumeLogMapper;
    private final CommissionPerformanceQueryPort performanceQueryPort;
    private final PeriodCloseQueryPort periodCloseQueryPort;
    private final EmployeeMainDataQueryPort employeeMainDataQueryPort;
    private final EventPort eventPort;
    private final WorkflowService workflowService;
    private final ConfigService configService;

    // ==================== 发起结佣（按合同） ====================

    /**
     * 发起结佣（拉取该合同当月事实 → 生成明细，§4.1）。
     * <p>
     * 新流程前置（§3.2）：合同实收事实必须已完成实收业绩审批（received APPROVED），否则拒绝。
     *
     * @param period     业绩归属月（结算月 YYYY-MM）
     * @param contractNo 合同号
     * @param operatorId 发起人 ID
     * @return 申请单（草稿）
     */
    @Transactional(rollbackFor = Exception.class)
    public CommissionApplication apply(String period, String contractNo, Long operatorId) {
        if (StringUtils.isBlank(period) || StringUtils.isBlank(contractNo)) {
            throw new ServiceException("结算月与合同号不能为空");
        }
        checkPeriodOpen(period, "发起结佣");
        return doApply(period, contractNo, operatorId);
    }

    /**
     * 批量发起：为期间内所有「未发起且有非零实收」的合同逐张建单（草稿，不自动提交）。
     */
    @Transactional(rollbackFor = Exception.class)
    public int batchApply(String period, Long deptId, Long operatorId) {
        if (StringUtils.isBlank(period)) {
            throw new ServiceException("结算月不能为空");
        }
        checkPeriodOpen(period, "批量发起结佣");

        List<PerformanceContractSummaryDTO> contracts =
            performanceQueryPort.listContractSummaries(period, deptId, FACT_TYPE_REAL);
        Set<String> activeContractNos = listActiveApplications(period).stream()
            .map(CommissionApplication::getContractNo)
            .collect(Collectors.toSet());

        int created = 0;
        List<String> failed = new ArrayList<>();
        for (PerformanceContractSummaryDTO contract : contracts) {
            if (contract.getAmount() == null || contract.getAmount().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            if (activeContractNos.contains(contract.getContractNo())) {
                continue;
            }
            if (!"APPROVED".equals(contract.getReceivedStatus())) {
                continue;
            }
            try {
                doApply(period, contract.getContractNo(), operatorId);
                created++;
            } catch (ServiceException e) {
                // 单合同失败（如实收未审批 / 员工无法归属）不阻断整批，收集后统一提示
                log.warn("[结佣-批量发起] 合同 {} 发起失败：{}", contract.getContractNo(), e.getMessage());
                failed.add(contract.getContractNo());
            }
        }
        log.info("[结佣-批量发起] period={}, deptId={}, 创建={}, 失败={}", period, deptId, created, failed.size());
        if (!failed.isEmpty()) {
            throw new ServiceException("成功发起 " + created + " 张；以下合同失败：" + failed);
        }
        return created;
    }

    /**
     * Excel 批量发起（§3.2）：按表内合同号逐张 发起+自动提交；金额列仅用于展示不参与校验。
     */
    @Transactional(rollbackFor = Exception.class)
    public CommissionBatchResult batchInitiate(String period, MultipartFile file, Long operatorId) {
        if (StringUtils.isBlank(period)) {
            throw new ServiceException("结算月不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new ServiceException("请上传 Excel 文件（.xlsx/.xls）");
        }
        List<CommissionBatchExcelParser.ContractAmountRow> rows;
        try {
            rows = CommissionBatchExcelParser.parse(file.getInputStream());
        } catch (Exception e) {
            throw new ServiceException("Excel 读取失败：{}", e.getMessage());
        }
        CommissionBatchResult result = new CommissionBatchResult();
        Set<String> seen = new HashSet<>();
        for (CommissionBatchExcelParser.ContractAmountRow row : rows) {
            if (!seen.add(row.getContractNo())) {
                continue;
            }
            try {
                CommissionApplication application;
                CommissionApplication existing = findActiveApplication(period, row.getContractNo());
                if (existing != null
                    && existing.getStatus() != ApplicationStatus.CANCELLED) {
                    // 已有未完结单：草稿/驳回单直接提交，审批中/已锁定视为成功跳过
                    if (existing.getStatus() == ApplicationStatus.DRAFT
                        || existing.getStatus() == ApplicationStatus.REJECTED) {
                        application = existing;
                    } else {
                        result.addSuccess();
                        continue;
                    }
                } else {
                    checkPeriodOpen(period, "批量发起结佣");
                    application = doApply(period, row.getContractNo(), operatorId);
                }
                submit(application.getId(), operatorId);
                result.addSuccess();
            } catch (Exception e) {
                result.addFailure(row.getContractNo(), row.getAmountText(), e.getMessage());
            }
        }
        log.info("[结佣-Excel批量发起] period={}, 成功={}, 失败={}",
            period, result.getSuccessCount(), result.getFailedRows().size());
        return result;
    }

    /**
     * 发起核心逻辑（不含封账校验，由公共入口保证）。
     */
    private CommissionApplication doApply(String period, String contractNo, Long operatorId) {
        // 幂等检查：该 (period, contractNo) 的未完结申请单
        CommissionApplication existing = findActiveApplication(period, contractNo);
        if (existing != null) {
            if (existing.getStatus() == ApplicationStatus.APPROVED
                || existing.getStatus() == ApplicationStatus.LOCKED) {
                throw new ServiceException("合同 " + contractNo + " " + period
                    + " 月结佣已审批锁定，新增或变更一律走调整单");
            }
            throw new ServiceException("合同 " + contractNo + " " + period
                + " 月已存在" + existing.getStatus().getDesc() + "申请单（" + existing.getApplyNo() + "），请勿重复发起");
        }

        // 拉取该合同 ACTIVE 实收事实（含 0 值，本域过滤）
        List<PerformanceFactSummaryDTO> facts = performanceQueryPort
            .findActiveByContract(period, contractNo, FACT_TYPE_REAL);
        List<PerformanceFactSummaryDTO> nonZeroFacts = filterNonZero(facts);
        if (nonZeroFacts.isEmpty()) {
            throw new ServiceException("合同 " + contractNo + " " + period + " 月无可入账的实收业绩（amount>0 的实收事实为 0 条）");
        }

        // §3.2 前置校验：仅可对实收审批通过的业绩发起结佣
        List<String> unapproved = nonZeroFacts.stream()
            .filter(f -> f.getReceivedApplyId() == null || !"APPROVED".equals(f.getReceivedStatus()))
            .map(f -> "事实" + f.getFactId() + "(" + f.getReceivedStatus() + ")")
            .distinct()
            .toList();
        if (!unapproved.isEmpty()) {
            throw new ServiceException("合同 " + contractNo + " 的实收业绩尚未完成实收审批（§3.2），"
                + "不能发起结佣；未通过明细：" + unapproved);
        }

        // 员工归属兜底：明细 employee_id NOT NULL，按工号补齐
        resolveEmployeeIds(nonZeroFacts);

        // 合同快照（订单号/房源/签约时间取事实聚合值；跨门店合作单 dept_id 留空）
        PerformanceFactSummaryDTO first = nonZeroFacts.get(0);
        String orderNo = null;
        String propertyAddress = null;
        LocalDate businessDate = null;
        Set<Long> deptIds = new HashSet<>();
        for (PerformanceFactSummaryDTO f : nonZeroFacts) {
            if (orderNo == null) {
                orderNo = f.getOrderNo();
            }
            if (propertyAddress == null) {
                propertyAddress = f.getPropertyAddress();
            }
            if (f.getBusinessDate() != null
                && (businessDate == null || f.getBusinessDate().isAfter(businessDate))) {
                businessDate = f.getBusinessDate();
            }
            if (f.getDeptId() != null) {
                deptIds.add(f.getDeptId());
            }
        }

        CommissionApplication application = new CommissionApplication();
        application.setApplyNo("CAPP" + LocalDateTime.now().format(APPLY_NO_FORMATTER));
        application.setPeriod(period);
        application.setContractNo(contractNo);
        application.setOrderNo(orderNo);
        application.setPropertyAddress(propertyAddress);
        application.setBusinessDate(businessDate != null ? businessDate.atStartOfDay() : null);
        application.setDeptId(deptIds.size() == 1 ? first.getDeptId() : null);
        application.setStatus(ApplicationStatus.DRAFT);
        application.setApplicantId(operatorId);
        application.setItemCount(nonZeroFacts.size());
        application.setTotalAmount(sumAmounts(nonZeroFacts));
        application.setExpectedAmount(resolveExpectedAmount(period, contractNo));
        application.setAligned(false);
        try {
            applicationMapper.insert(application);
        } catch (DuplicateKeyException e) {
            // 并发发起撞 uk_capp_period_contract 部分唯一索引 → 转友好提示
            throw new ServiceException("合同 " + contractNo + " " + period + " 月申请单已由他人发起，请刷新");
        }

        for (PerformanceFactSummaryDTO fact : nonZeroFacts) {
            itemMapper.insert(buildItem(application, fact, null));
        }

        log.info("[结佣-发起] 合同申请单已创建：applyNo={}, period={}, contractNo={}, itemCount={}, received={}, expected={}",
            application.getApplyNo(), period, contractNo, application.getItemCount(),
            application.getTotalAmount(), application.getExpectedAmount());
        return application;
    }

    /** 应收合计：取业绩域合同汇总的应收列（PERF_EXPECT 合计）。 */
    private BigDecimal resolveExpectedAmount(String period, String contractNo) {
        return performanceQueryPort.listContractSummaries(period, null, FACT_TYPE_REAL).stream()
            .filter(c -> contractNo.equals(c.getContractNo()))
            .findFirst()
            .map(PerformanceContractSummaryDTO::getExpectedAmount)
            .orElse(BigDecimal.ZERO);
    }

    /**
     * 0 值过滤（纯函数，供单测）：仅保留 amount &lt;&gt; 0 的事实（BigDecimal compareTo 比较）。
     */
    public static List<PerformanceFactSummaryDTO> filterNonZero(List<PerformanceFactSummaryDTO> facts) {
        if (facts == null || facts.isEmpty()) {
            return new ArrayList<>();
        }
        List<PerformanceFactSummaryDTO> result = new ArrayList<>(facts.size());
        for (PerformanceFactSummaryDTO fact : facts) {
            if (fact.getAmount() != null && fact.getAmount().compareTo(BigDecimal.ZERO) != 0) {
                result.add(fact);
            }
        }
        return result;
    }

    // ==================== 提交 / 审批（workflow） ====================

    /**
     * 提交审批（§3.1）：DRAFT / REJECTED → SUBMITTED，启动 commission_apply 流程。
     * <p>
     * 发起人路由：申请人节点办理后进入总监节点；若发起人=总监（或超管），
     * 系统自动办理总监节点（含 §3.5 差异对齐判定），无差异时继续自动过财务直至完成。
     */
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long applicationId, Long operatorId) {
        CommissionApplication application = getApplication(applicationId);
        if (application.getStatus() != ApplicationStatus.DRAFT
            && application.getStatus() != ApplicationStatus.REJECTED) {
            throw new ServiceException("仅草稿/已驳回状态可提交（当前：" + application.getStatus().getDesc() + "）");
        }
        application.setStatus(ApplicationStatus.SUBMITTED);
        applicationMapper.updateById(application);
        // 草稿明细随单流转：DRAFT → PENDING；驳回单明细已是 PENDING
        itemMapper.update(null, new LambdaUpdateWrapper<CommissionItem>()
            .eq(CommissionItem::getApplicationId, applicationId)
            .eq(CommissionItem::getStatus, ItemStatus.DRAFT)
            .set(CommissionItem::getStatus, ItemStatus.PENDING));

        if (StringUtils.isBlank(application.getProcessInstanceId())) {
            startWorkflow(application);
        } else {
            // 驳回后流程停在申请人节点：办理申请人任务重新提交
            Long taskId = workflowService.getCurrentTaskId(String.valueOf(applicationId));
            if (taskId == null) {
                throw new ServiceException("审批流程任务不存在，请联系管理员");
            }
            workflowService.completeTask(taskId, "重新提交");
        }

        // 发起人=总监 → 系统自动办理总监节点（§3.1 总监发起）
        Set<String> roles = currentRoles();
        if (roles.contains("director")) {
            doDirectorApprove(application, operatorId, "总监发起，系统自动审批");
        }
        refreshCurrentNode(application);
        log.info("[结佣-提交] 合同申请单已提交：applyNo={}, contractNo={}, operator={}, node={}",
            application.getApplyNo(), application.getContractNo(), operatorId, application.getCurrentNode());
    }

    /**
     * 单个审批通过（§3.3）：按当前待办节点自动识别。
     * <ul>
     *   <li>总监节点：先做 §3.5 差异判定与实收对齐，办理后无差异/跳过财务则系统自动过财务；</li>
     *   <li>财务节点：直接办理，流程结束 → finish 事件锁定单据。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long applicationId) {
        CommissionApplication application = requireSubmitted(applicationId);
        String node = workflowService.getCurrentNodeCode(String.valueOf(applicationId));
        if (NODE_DIRECTOR.equals(node)) {
            // 总监节点：仅总监可办理（引擎按 flow_user 名单判权，越权直接拒绝）
            assertCurrentNodeHandler(application, "审批");
            doDirectorApprove(application, LoginHelper.getUserId(), "总监审批通过");
        } else if (NODE_FINANCE.equals(node)) {
            assertCurrentNodeHandler(application, "审批");
            Long taskId = workflowService.getCurrentTaskId(String.valueOf(applicationId));
            if (taskId == null) {
                throw new ServiceException("当前无待办任务");
            }
            CompleteTaskDTO completeTask = new CompleteTaskDTO();
            completeTask.setTaskId(taskId);
            completeTask.setMessage("财务审批通过");
            completeTaskAsLoginUser(completeTask);
        } else {
            throw new ServiceException("当前无可审批节点（节点=" + node + "）");
        }
        refreshCurrentNode(application);
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
     * （capp_director → role:…010 总监、capp_finance → role:…012 财务）保持一致。</p>
     *
     * @param action 动作名，用于拼装错误提示（审批 / 驳回）
     */
    private void assertCurrentNodeHandler(CommissionApplication application, String action) {
        String nodeCode = workflowService.getCurrentNodeCode(String.valueOf(application.getId()));
        String requiredRole;
        String requiredRoleName;
        if (NODE_DIRECTOR.equals(nodeCode)) {
            requiredRole = ROLE_DIRECTOR;
            requiredRoleName = "总监";
        } else if (NODE_FINANCE.equals(nodeCode)) {
            requiredRole = ROLE_FINANCE;
            requiredRoleName = "财务";
        } else {
            throw new ServiceException("该单据当前不在可审批节点，无法" + action);
        }
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        if (!currentRoles().contains(requiredRole)) {
            throw new ServiceException("该单据当前由「" + requiredRoleName + "」办理，您无权" + action);
        }
    }

    /**
     * Excel 批量审批（§3.3）：匹配 合同号+实收金额 与 SUBMITTED 单据，按当前节点逐张通过。
     */
    @Transactional(rollbackFor = Exception.class)
    public CommissionBatchResult batchApprove(String period, MultipartFile file) {
        if (StringUtils.isBlank(period)) {
            throw new ServiceException("结算月不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new ServiceException("请上传 Excel 文件（.xlsx/.xls）");
        }
        List<CommissionBatchExcelParser.ContractAmountRow> rows;
        try {
            rows = CommissionBatchExcelParser.parse(file.getInputStream());
        } catch (Exception e) {
            throw new ServiceException("Excel 读取失败：{}", e.getMessage());
        }
        CommissionBatchResult result = new CommissionBatchResult();
        for (CommissionBatchExcelParser.ContractAmountRow row : rows) {
            try {
                BigDecimal amount = CommissionBatchExcelParser.parseAmount(row.getAmountText());
                CommissionApplication application = applicationMapper.selectOne(
                    new LambdaQueryWrapper<CommissionApplication>()
                        .eq(CommissionApplication::getPeriod, period)
                        .eq(CommissionApplication::getContractNo, row.getContractNo())
                        .eq(CommissionApplication::getStatus, ApplicationStatus.SUBMITTED)
                        .orderByDesc(CommissionApplication::getId)
                        .last("LIMIT 1"));
                if (application == null) {
                    result.addFailure(row.getContractNo(), row.getAmountText(), "无审批中的结佣申请单");
                    continue;
                }
                if (amount == null) {
                    result.addFailure(row.getContractNo(), row.getAmountText(), "金额无法识别");
                    continue;
                }
                if (application.getTotalAmount() == null
                    || application.getTotalAmount().compareTo(amount) != 0) {
                    result.addFailure(row.getContractNo(), row.getAmountText(),
                        "金额不匹配，单据实收=" + application.getTotalAmount());
                    continue;
                }
                approve(application.getId());
                result.addSuccess();
            } catch (Exception e) {
                result.addFailure(row.getContractNo(), row.getAmountText(), e.getMessage());
            }
        }
        log.info("[结佣-Excel批量审批] period={}, 成功={}, 失败={}",
            period, result.getSuccessCount(), result.getFailedRows().size());
        return result;
    }

    /**
     * 作废申请单：DRAFT/SUBMITTED 可作废；运行中的流程先终止（cancel 事件回调冲销明细）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long applicationId, Long operatorId) {
        CommissionApplication application = getApplication(applicationId);
        if (application.getStatus() != ApplicationStatus.DRAFT
            && application.getStatus() != ApplicationStatus.SUBMITTED) {
            throw new ServiceException("仅草稿/已提交状态可作废（当前：" + application.getStatus().getDesc() + "）");
        }
        if (StringUtils.isNotBlank(application.getProcessInstanceId())) {
            // 终止运行中的流程实例（触发 cancel 事件，监听器置 CANCELLED + 冲销明细，幂等）
            workflowService.deleteInstance(List.of(String.valueOf(applicationId)));
        }
        // 草稿无流程实例：本地直接置 CANCELLED 并冲销明细
        reverseUnapprovedItems(applicationId);
        // 明细 UPDATE 会清空 MyBatis 一级缓存并推进数据版本，这里重新加载避免乐观锁更新丢失
        application = applicationMapper.selectById(applicationId);
        if (application != null && application.getStatus() != ApplicationStatus.CANCELLED) {
            application.setItemCount(0);
            application.setTotalAmount(BigDecimal.ZERO);
            application.setStatus(ApplicationStatus.CANCELLED);
            application.setCurrentNode(null);
            applicationMapper.updateById(application);
        }
        log.info("[结佣-作废] applyNo={}, operator={}",
            application == null ? applicationId : application.getApplyNo(), operatorId);
    }

    // ==================== 工作流回调 ====================

    /**
     * commission_apply 流程事件处理（CommissionApplyWorkflowListener 调用）。
     * <ul>
     *   <li>finish：SUBMITTED → LOCKED，落 approved_month=当前月，明细 PENDING→APPROVED，发结佣通过事件；</li>
     *   <li>back：→ REJECTED（明细保持 PENDING）；</li>
     *   <li>cancel/invalid/termination：→ CANCELLED，未审批明细冲销释放事实。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleWorkflowEvent(Long applicationId, String status, String handler, String message) {
        CommissionApplication application = applicationMapper.selectById(applicationId);
        if (application == null) {
            log.warn("[结佣工作流] 申请单不存在，忽略：id={}, status={}", applicationId, status);
            return;
        }
        Long handlerId = parseHandlerId(handler);
        switch (status == null ? "" : status) {
            case "finish" -> {
                if (application.getStatus() == ApplicationStatus.LOCKED) {
                    return;
                }
                String approvedMonth = LocalDateTime.now().format(PERIOD_FORMATTER);
                List<CommissionItem> pendingItems = itemMapper.selectList(new LambdaQueryWrapper<CommissionItem>()
                    .eq(CommissionItem::getApplicationId, applicationId)
                    .eq(CommissionItem::getStatus, ItemStatus.PENDING));
                itemMapper.update(null, new LambdaUpdateWrapper<CommissionItem>()
                    .eq(CommissionItem::getApplicationId, applicationId)
                    .eq(CommissionItem::getStatus, ItemStatus.PENDING)
                    .set(CommissionItem::getStatus, ItemStatus.APPROVED)
                    .set(CommissionItem::getApprovedMonth, approvedMonth));
                application.setStatus(ApplicationStatus.LOCKED);
                application.setCurrentNode(null);
                application.setApprovedMonth(approvedMonth);
                if (handlerId != null) {
                    application.setApproverId(handlerId);
                }
                application.setLockTime(LocalDateTime.now());
                applicationMapper.updateById(application);

                CommissionApprovedEvent event = new CommissionApprovedEvent();
                event.setApplicationId(applicationId);
                event.setPeriod(application.getPeriod());
                event.setApprovedMonth(approvedMonth);
                event.setDeptId(application.getDeptId());
                event.setItemIds(pendingItems.stream().map(i -> String.valueOf(i.getId())).toList());
                eventPort.emit(event);
                log.info("[结佣工作流] 审批通过已锁定：id={}, applyNo={}, approvedMonth={}, items={}",
                    applicationId, application.getApplyNo(), approvedMonth, pendingItems.size());
            }
            case "back" -> {
                if (application.getStatus() != ApplicationStatus.SUBMITTED) {
                    return;
                }
                application.setStatus(ApplicationStatus.REJECTED);
                application.setCurrentNode(null);
                applicationMapper.updateById(application);
                log.info("[结佣工作流] 驳回：id={}, message={}", applicationId, message);
            }
            case "cancel", "invalid", "termination" -> {
                reverseUnapprovedItems(applicationId);
                application.setItemCount(0);
                application.setTotalAmount(BigDecimal.ZERO);
                application.setStatus(ApplicationStatus.CANCELLED);
                application.setCurrentNode(null);
                applicationMapper.updateById(application);
                log.info("[结佣工作流] 作废/终止：id={}", applicationId);
            }
            default -> log.info("[结佣工作流] 忽略状态：id={}, status={}", applicationId, status);
        }
    }

    // ==================== 查询 ====================

    public PageResult<CommissionApplication> listApplications(ApplyQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<CommissionApplication> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.isNotBlank(query.getPeriod()), CommissionApplication::getPeriod, query.getPeriod())
            .eq(query.getDeptId() != null, CommissionApplication::getDeptId, query.getDeptId())
            .eq(StringUtils.isNotBlank(query.getStatus()), CommissionApplication::getStatus,
                ApplicationStatus.fromCode(query.getStatus()))
            .and(StringUtils.isNotBlank(query.getKeyword()), w -> w
                .like(CommissionApplication::getContractNo, query.getKeyword())
                .or().like(CommissionApplication::getOrderNo, query.getKeyword())
                .or().like(CommissionApplication::getPropertyAddress, query.getKeyword()))
            .orderByDesc(CommissionApplication::getCreateTime);
        var page = applicationMapper.selectPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    /**
     * 按「合同」维度分页查询结佣申请（与业绩明细页合同维度对齐）。
     */
    public PageResult<CommissionContractVO> listContracts(ApplyQuery query, PageQuery pageQuery) {
        String period = StringUtils.isNotBlank(query.getPeriod())
            ? query.getPeriod() : LocalDateTime.now().format(PERIOD_FORMATTER);

        List<PerformanceContractSummaryDTO> contracts =
            performanceQueryPort.listContractSummaries(period, query.getDeptId(), FACT_TYPE_REAL);

        List<CommissionApplication> applications = applicationMapper.selectList(new LambdaQueryWrapper<CommissionApplication>()
            .eq(CommissionApplication::getPeriod, period)
            .orderByDesc(CommissionApplication::getId));
        Map<String, CommissionApplication> appMap = new LinkedHashMap<>();
        for (CommissionApplication app : applications) {
            appMap.putIfAbsent(app.getContractNo(), app);
        }

        String keyword = StringUtils.trimToNull(query.getKeyword());

        List<CommissionContractVO> all = new ArrayList<>(contracts.size());
        for (PerformanceContractSummaryDTO c : contracts) {
            CommissionApplication app = appMap.get(c.getContractNo());
            String status = app != null && app.getStatus() != null ? app.getStatus().getCode() : ROW_STATUS_NONE;
            if (app == null && !"APPROVED".equals(c.getReceivedStatus())) {
                continue;
            }
            if (StringUtils.isNotBlank(query.getStatus()) && !query.getStatus().equals(status)) {
                continue;
            }
            if (keyword != null && !containsKeyword(c, keyword)) {
                continue;
            }
            all.add(toContractVO(period, c, app, status));
        }

        all.sort((a, b) -> {
            if (a.getBusinessDate() == null && b.getBusinessDate() == null) {
                return 0;
            }
            if (a.getBusinessDate() == null) {
                return 1;
            }
            if (b.getBusinessDate() == null) {
                return -1;
            }
            return b.getBusinessDate().compareTo(a.getBusinessDate());
        });
        int total = all.size();
        int pageNum = pageQuery.getPageNum() != null ? pageQuery.getPageNum() : 1;
        int pageSize = pageQuery.getPageSize() != null ? pageQuery.getPageSize() : 20;
        int from = Math.min((pageNum - 1) * pageSize, total);
        int to = Math.min(from + pageSize, total);
        return PageResult.build(all.subList(from, to), (long) total);
    }

    /** 合同汇总 + 申请单 → 列表行 VO；有单时金额/条数以申请单聚合为准（0 值事实不入单） */
    private CommissionContractVO toContractVO(String period, PerformanceContractSummaryDTO c,
                                              CommissionApplication app, String status) {
        CommissionContractVO vo = new CommissionContractVO();
        vo.setPeriod(period);
        vo.setContractNo(c.getContractNo());
        vo.setOrderNo(c.getOrderNo());
        vo.setBizType(c.getBizType());
        vo.setPropertyAddress(c.getPropertyAddress());
        vo.setBusinessDate(c.getBusinessDate());
        vo.setEmployeeCount(c.getEmployeeCount());
        vo.setStatus(status);
        vo.setExpectedAmount(c.getExpectedAmount());
        vo.setReceivedStatus(c.getReceivedStatus());
        if (app != null) {
            vo.setApplicationId(app.getId());
            vo.setApplyNo(app.getApplyNo());
            vo.setApplicantId(app.getApplicantId());
            vo.setCreateTime(app.getCreateTime());
            vo.setDeptId(app.getDeptId());
            vo.setAmount(app.getTotalAmount());
            vo.setDetailCount(app.getItemCount() == null ? 0 : app.getItemCount());
            vo.setAligned(app.getAligned());
            vo.setCurrentNode(app.getCurrentNode());
            if (app.getExpectedAmount() != null) {
                vo.setExpectedAmount(app.getExpectedAmount());
            }
        } else {
            vo.setAmount(c.getAmount());
            vo.setDetailCount(c.getDetailCount());
        }
        return vo;
    }

    private boolean containsKeyword(PerformanceContractSummaryDTO c, String keyword) {
        return (c.getContractNo() != null && c.getContractNo().contains(keyword))
            || (c.getOrderNo() != null && c.getOrderNo().contains(keyword))
            || (c.getPropertyAddress() != null && c.getPropertyAddress().contains(keyword));
    }

    public CommissionApplication getApplication(Long applicationId) {
        CommissionApplication application = applicationMapper.selectById(applicationId);
        if (application == null) {
            throw new ServiceException("结佣申请单不存在：" + applicationId);
        }
        return application;
    }

    public List<CommissionItem> listItems(Long applicationId) {
        return itemMapper.selectList(new LambdaQueryWrapper<CommissionItem>()
            .eq(CommissionItem::getApplicationId, applicationId)
            .orderByAsc(CommissionItem::getId));
    }

    /**
     * 查询申请单下每人结佣明细详情（列口径对齐实收明细详情）。
     * 过滤掉已冲销（REVERSED）行。
     */
    public List<com.panjia.commission.dto.CommissionItemDetailDTO> listItemDetails(Long applicationId) {
        return itemMapper.selectItemDetails(applicationId);
    }

    public CommissionItem getItem(Long itemId) {
        return itemMapper.selectById(itemId);
    }

    public PageResult<CommissionItem> listItems(com.panjia.commission.dto.ItemQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<CommissionItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.isNotBlank(query.getPeriod()), CommissionItem::getPeriod, query.getPeriod())
            .eq(query.getEmployeeId() != null, CommissionItem::getEmployeeId, query.getEmployeeId())
            .eq(query.getDeptId() != null, CommissionItem::getDeptId, query.getDeptId())
            .eq(StringUtils.isNotBlank(query.getStatus()), CommissionItem::getStatus,
                ItemStatus.fromCode(query.getStatus()))
            .eq(query.getApplicationId() != null, CommissionItem::getApplicationId, query.getApplicationId())
            .orderByDesc(CommissionItem::getCreateTime);
        var page = itemMapper.selectPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    public PageResult<CommissionConsumeLog> listConsumeLogs(com.panjia.commission.dto.ConsumeLogQuery query,
                                                            PageQuery pageQuery) {
        LambdaQueryWrapper<CommissionConsumeLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.isNotBlank(query.getPeriod()), CommissionConsumeLog::getPeriod, query.getPeriod())
            .eq(StringUtils.isNotBlank(query.getEventType()), CommissionConsumeLog::getEventType, query.getEventType())
            .eq(StringUtils.isNotBlank(query.getEventId()), CommissionConsumeLog::getEventId, query.getEventId())
            .orderByDesc(CommissionConsumeLog::getCreateTime);
        var page = consumeLogMapper.selectPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    // ==================== 共用内部逻辑 ====================

    /**
     * 重算申请单聚合（item_count / total_amount，按未 REVERSED 明细）。
     */
    public void recalcAggregates(Long applicationId, CommissionApplication application) {
        CommissionApplication app = application != null ? application : applicationMapper.selectById(applicationId);
        if (app == null) {
            return;
        }
        List<CommissionItem> items = itemMapper.selectList(new LambdaQueryWrapper<CommissionItem>()
            .eq(CommissionItem::getApplicationId, applicationId)
            .ne(CommissionItem::getStatus, ItemStatus.REVERSED));
        app.setItemCount(items.size());
        app.setTotalAmount(items.stream()
            .map(CommissionItem::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add));
        int rows = applicationMapper.updateById(app);
        if (rows == 0) {
            throw new ServiceException("申请单并发冲突，聚合重算失败，请重试：applicationId=" + applicationId);
        }
    }

    /**
     * 构建流程业务扩展信息，供「我的待办 / 我发起的」列表直接展示"在审什么"。
     * <p>
     * 不填的后果：flow_instance_biz_ext.business_title 为空，待办列表业务编码/业务标题两列全空，
     * 审批人只能看到一串技术编码，无法分辨审的是哪张单。
     */
    private FlowInstanceBizExtDTO buildBizExt(CommissionApplication application) {
        FlowInstanceBizExtDTO bizExt = new FlowInstanceBizExtDTO();
        bizExt.setBusinessId(String.valueOf(application.getId()));
        bizExt.setBusinessCode(text(application.getApplyNo()));
        bizExt.setBusinessTitle("结佣审批｜" + text(application.getContractNo())
            + " " + text(application.getPropertyAddress())
            + "｜账期" + text(application.getPeriod())
            + "｜应收" + text(application.getTotalAmount()));
        return bizExt;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 启动 commission_apply 流程并办理申请人首节点。
     */
    private void startWorkflow(CommissionApplication application) {
        StartProcessDTO start = new StartProcessDTO();
        start.setBusinessId(String.valueOf(application.getId()));
        start.setFlowCode(FLOW_CODE);
        Map<String, Object> variables = new HashMap<>(2);
        variables.put("ignore", true);
        start.setVariables(variables);
        start.setBizExt(buildBizExt(application));
        try {
            boolean ok = workflowService.startCompleteTask(start);
            if (!ok) {
                throw new ServiceException("结佣审批流程发起失败");
            }
        } catch (Exception e) {
            log.error("[结佣] 流程发起异常：id={}", application.getId(), e);
            throw new ServiceException("结佣审批流程发起失败：{}", e.getMessage());
        }
        Long instanceId = workflowService.getInstanceIdByBusinessId(String.valueOf(application.getId()));
        if (instanceId != null) {
            application.setProcessInstanceId(String.valueOf(instanceId));
            applicationMapper.updateById(application);
        }
    }

    /**
     * 总监节点审批处理（§3.4/§3.5）：
     * <ol>
     *   <li>比对单内实收合计与应收合计：有差异且未对齐 → 调业绩域对齐端口，
     *       实收事实（合同+每人明细）supersede 为应收口径，结佣明细按映射重绑事实+金额并重算；</li>
     *   <li>办理总监任务；</li>
     *   <li>无差异（或全局 skip_finance）→ 系统自动办理财务节点，流程结束；有差异 → 停留财务人工审批。</li>
     * </ol>
     */
    private void doDirectorApprove(CommissionApplication application, Long operatorId, String message) {
        Long directorTask = taskAtNode(application.getId(), NODE_DIRECTOR);
        if (directorTask == null) {
            throw new ServiceException("当前不在总监审批节点");
        }
        BigDecimal received = application.getTotalAmount() == null ? BigDecimal.ZERO : application.getTotalAmount();
        BigDecimal expected = application.getExpectedAmount() == null
            ? BigDecimal.ZERO : application.getExpectedAmount();
        boolean hasDiff = received.compareTo(expected) != 0;

        if (hasDiff && !Boolean.TRUE.equals(application.getAligned())) {
            log.info("[结佣-对齐] 实收与应收存在差异，触发自动对齐：id={}, received={}, expected={}",
                application.getId(), received, expected);
            ReceivedAlignmentResultDTO result = performanceQueryPort.alignReceivedToExpected(
                application.getPeriod(), application.getContractNo(), operatorId);
            rebindItemsAfterAlignment(application, result);
            application.setAligned(true);
            recalcAggregates(application.getId(), application);
        }

        // 总监本人办理：不设 ignore，交由引擎按 flow_user 名单判权（双保险，越权直接拒绝）
        CompleteTaskDTO directorComplete = new CompleteTaskDTO();
        directorComplete.setTaskId(directorTask);
        directorComplete.setMessage(StringUtils.isBlank(message) ? "总监审批通过" : message
            + (hasDiff ? "（实收已自动对齐应收，转财务复核）" : ""));
        completeTaskAsLoginUser(directorComplete);

        // §3.4 无差异不流转财务（或全局跳过财务）→ 系统自动完成财务节点
        // 注意：此处为「系统自动审批」（无登录办理人），必须保留 ignore=true，不属于越权。
        boolean skipFinance = Boolean.TRUE.equals(configService.getConfigBool(CONFIG_SKIP_FINANCE));
        if (!hasDiff || skipFinance) {
            Long financeTask = taskAtNode(application.getId(), NODE_FINANCE);
            if (financeTask != null) {
                workflowService.completeTask(financeTask,
                    hasDiff ? "全局跳过财务，系统自动通过" : "实收应收无差异，系统自动完成财务节点");
            }
        }
    }

    /**
     * 对齐后按 旧事实→新事实 映射重绑结佣明细：事实 ID / 金额 / 期间 / 门店 同步到新事实。
     */
    private void rebindItemsAfterAlignment(CommissionApplication application, ReceivedAlignmentResultDTO result) {
        if (result == null || result.getMappings() == null || result.getMappings().isEmpty()) {
            return;
        }
        for (ReceivedAlignmentResultDTO.Mapping mapping : result.getMappings()) {
            PerformanceFactSummaryDTO newFact = mapping.getNewFact();
            if (mapping.getOldFactId() == null || newFact == null) {
                continue;
            }
            itemMapper.update(null, new LambdaUpdateWrapper<CommissionItem>()
                .eq(CommissionItem::getApplicationId, application.getId())
                .eq(CommissionItem::getPerformanceFactId, mapping.getOldFactId())
                .ne(CommissionItem::getStatus, ItemStatus.REVERSED)
                .set(CommissionItem::getPerformanceFactId, newFact.getFactId())
                .set(CommissionItem::getAmount, newFact.getAmount())
                .set(newFact.getPeriod() != null, CommissionItem::getPeriod, newFact.getPeriod())
                .set(newFact.getDeptId() != null, CommissionItem::getDeptId, newFact.getDeptId()));
        }
    }

    private Long taskAtNode(Long applicationId, String nodeCode) {
        String current = workflowService.getCurrentNodeCode(String.valueOf(applicationId));
        return nodeCode.equals(current) ? workflowService.getCurrentTaskId(String.valueOf(applicationId)) : null;
    }

    /** 从工作流回写当前节点（capp_director→DIRECTOR / capp_finance→FINANCE / 已结束→null）。 */
    private void refreshCurrentNode(CommissionApplication application) {
        String nodeCode = workflowService.getCurrentNodeCode(String.valueOf(application.getId()));
        String shortNode;
        if (NODE_DIRECTOR.equals(nodeCode)) {
            shortNode = "DIRECTOR";
        } else if (NODE_FINANCE.equals(nodeCode)) {
            shortNode = "FINANCE";
        } else {
            shortNode = null;
        }
        application.setCurrentNode(shortNode);
        applicationMapper.updateById(application);
    }

    /**
     * 未审批明细（DRAFT/PENDING）随单冲销，释放业绩事实。
     * 注意：只更新明细，不回写申请单聚合——申请单的状态/聚合由调用方在自己持有的版本对象上更新，
     * 避免与调用方形成「一级缓存分叉 + @Version 乐观锁」导致的更新丢失。
     */
    private int reverseUnapprovedItems(Long applicationId) {
        return itemMapper.update(null, new LambdaUpdateWrapper<CommissionItem>()
            .eq(CommissionItem::getApplicationId, applicationId)
            .in(CommissionItem::getStatus, ItemStatus.DRAFT, ItemStatus.PENDING)
            .set(CommissionItem::getStatus, ItemStatus.REVERSED)
            .set(CommissionItem::getReversedReason, ReversedReason.APPLICATION_CANCELLED));
    }

    private CommissionApplication requireSubmitted(Long applicationId) {
        CommissionApplication application = getApplication(applicationId);
        if (application.getStatus() != ApplicationStatus.SUBMITTED) {
            throw new ServiceException("仅审批中的单据可办理（当前：" + application.getStatus().getDesc() + "）");
        }
        return application;
    }

    private List<CommissionApplication> listActiveApplications(String period) {
        return applicationMapper.selectList(new LambdaQueryWrapper<CommissionApplication>()
            .eq(CommissionApplication::getPeriod, period)
            .in(CommissionApplication::getStatus, ApplicationStatus.DRAFT, ApplicationStatus.SUBMITTED,
                ApplicationStatus.APPROVED, ApplicationStatus.LOCKED));
    }

    private CommissionApplication findActiveApplication(String period, String contractNo) {
        return applicationMapper.selectOne(new LambdaQueryWrapper<CommissionApplication>()
            .eq(CommissionApplication::getPeriod, period)
            .eq(CommissionApplication::getContractNo, contractNo)
            .in(CommissionApplication::getStatus, ApplicationStatus.DRAFT, ApplicationStatus.SUBMITTED,
                ApplicationStatus.APPROVED, ApplicationStatus.LOCKED)
            .orderByDesc(CommissionApplication::getCreateTime)
            .last("LIMIT 1"));
    }

    /**
     * 封账校验：CLOSED 期间结佣窗口关闭（§2.5），严禁系统自动放行。
     */
    private void checkPeriodOpen(String period, String action) {
        if (periodCloseQueryPort.isClosed(period)) {
            throw new ServiceException("[" + action + "] 期间 " + period + " 已封账，结佣窗口关闭；"
                + "如有实收未结，请以差额调整（DIFF）补发到未封账月份，或由总监解封后重走");
        }
    }

    /**
     * 员工归属兜底（就地修改 fact.employeeId）：事实 employee_id 为空时按工号批量补齐，
     * 仍无法归属的整体拒绝，fail fast（明细 employee_id NOT NULL）。
     */
    private void resolveEmployeeIds(List<PerformanceFactSummaryDTO> facts) {
        Set<String> missingCodes = new HashSet<>();
        for (PerformanceFactSummaryDTO fact : facts) {
            if (fact.getEmployeeId() == null && StringUtils.isNotBlank(fact.getEmployeeCode())) {
                missingCodes.add(fact.getEmployeeCode());
            }
        }
        if (missingCodes.isEmpty()) {
            return;
        }
        Map<String, EmployeeMainDataDTO> mainMap = employeeMainDataQueryPort.listByCodes(missingCodes);
        List<String> unresolved = new ArrayList<>();
        for (PerformanceFactSummaryDTO fact : facts) {
            if (fact.getEmployeeId() != null) {
                continue;
            }
            EmployeeMainDataDTO main = mainMap.get(fact.getEmployeeCode());
            if (main != null && main.getEmployeeId() != null) {
                fact.setEmployeeId(main.getEmployeeId());
                if (fact.getDeptId() == null) {
                    fact.setDeptId(main.getDeptId());
                }
            } else {
                unresolved.add(fact.getEmployeeCode());
            }
        }
        if (!unresolved.isEmpty()) {
            throw new ServiceException("以下工号无法归属员工（请先在员工域补齐主数据）：" + unresolved);
        }
    }

    /**
     * 由事实构建结佣明细（amount 原样透传，不折算；冻结 contractNo/employee/dept/bizType/roleType）。
     */
    private CommissionItem buildItem(CommissionApplication application, PerformanceFactSummaryDTO fact, Long adjustId) {
        CommissionItem item = new CommissionItem();
        item.setApplicationId(application.getId());
        item.setPerformanceFactId(fact.getFactId());
        item.setContractNo(application.getContractNo());
        item.setPeriod(fact.getPeriod());
        item.setEmployeeId(fact.getEmployeeId());
        item.setDeptId(fact.getDeptId());
        item.setBizType(fact.getBizType());
        item.setRoleType(fact.getRoleType());
        item.setAmount(fact.getAmount());
        item.setStatus(ItemStatus.DRAFT);
        item.setOriginReversed(false);
        item.setAdjustId(adjustId);
        return item;
    }

    /** 事实金额合计 */
    private BigDecimal sumAmounts(List<PerformanceFactSummaryDTO> facts) {
        return facts.stream()
            .map(PerformanceFactSummaryDTO::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Set<String> currentRoles() {
        try {
            if (LoginHelper.isLogin() && LoginHelper.getLoginUser() != null) {
                Set<String> roles = LoginHelper.getLoginUser().getRolePermission();
                if (LoginHelper.isSuperAdmin()) {
                    roles = roles == null ? new HashSet<>() : new HashSet<>(roles);
                    roles.add("director");
                }
                return roles == null ? Set.of() : roles;
            }
        } catch (Exception e) {
            log.debug("[结佣] 无登录上下文：{}", e.getMessage());
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
