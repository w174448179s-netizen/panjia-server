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
import com.panjia.commission.dto.BatchResultDTO;
import com.panjia.commission.dto.CommissionContractVO;
import com.panjia.commission.mapper.CommissionApplicationMapper;
import com.panjia.commission.mapper.CommissionConsumeLogMapper;
import com.panjia.commission.mapper.CommissionItemMapper;
import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.contracts.dto.PerformanceContractSummaryDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.dto.ReceivedAlignmentResultDTO;
import com.panjia.contracts.event.CommissionApprovedEvent;
import com.panjia.contracts.event.EventPort;
import com.panjia.contracts.port.ApprovalAction;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ApprovalStartCmd;
import com.panjia.contracts.port.CommissionPerformanceQueryPort;
import com.panjia.contracts.port.EmployeeMainDataQueryPort;
import com.panjia.contracts.port.PeriodCloseQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.ConfigService;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.domain.DeptDTO;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
 * 结佣申请服务（按合同发起 / 提交 / 审批锁定，结佣域详细设计 §4.1~§4.2，新流程 §3）。
 * <p>
 * 申请单粒度 = <b>合同 + 业绩归属月</b>：一个合同当月一张申请单，独立提交、独立审批。
 * <p>
 * 新业务口径：
 * <ul>
 *   <li>仅可对<b>实收审批通过</b>（received_apply APPROVED）的实收业绩发起（§3.2）；</li>
 *   <li>金额为结佣业绩金额（PERF_REAL 原样透传），0 值实收不入单（ADR B14）；</li>
 *   <li>审批流 commission_apply：申请人 → 总监 → 财务；总监发起时系统自动过总监节点（§3.1）；</li>
 *   <li>实收=应收无差异：互斥网关 skip_condition 跳过财务，总监通过后直接结束（§3.4 / T-04）；</li>
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

    /** 业绩口径：应收业绩（参照口径，非审批对象） */
    private static final String FACT_TYPE_EXPECT = "PERF_EXPECT";

    private static final String NODE_DIRECTOR = "capp_director";
    private static final String NODE_FINANCE = "capp_finance";
    /** 业务角色标识，与 flow_node.permission_flag 的 role:…010 对应（总监发起自动判定用）。 */
    private static final String ROLE_DIRECTOR = "director";

    /** 配置开关：实收应收无差异时跳过财务节点（默认开启）。 */
    private static final String CONFIG_SKIP_FINANCE_WHEN_MATCH = "panjia.commission.skip_finance_when_match";

    /** 配置项：实收应收差异容忍阈值（元），默认 1。 */
    private static final String CONFIG_DIFF_TOLERANCE = "panjia.commission.diff_tolerance";
    private static final BigDecimal DEFAULT_DIFF_TOLERANCE = BigDecimal.ONE;

    /** 列表行虚拟状态：未发起（业绩存在但无申请单） */
    public static final String ROW_STATUS_NONE = "NONE";

    private final CommissionApplicationMapper applicationMapper;
    private final CommissionItemMapper itemMapper;
    private final CommissionConsumeLogMapper consumeLogMapper;
    private final CommissionPerformanceQueryPort performanceQueryPort;
    private final PeriodCloseQueryPort periodCloseQueryPort;
    private final EmployeeMainDataQueryPort employeeMainDataQueryPort;
    private final EventPort eventPort;
    private final ApprovalPort approvalPort;
    private final ConfigService configService;
    private final DeptService deptService;
    private final TaskExecutor taskExecutor;

    // ==================== 发起结佣（按合同） ====================

    /**
     * 门店数据权限校验：非超管用户只能发起归属部门为「本部门或本部门下级」的合同。
     * 判定方向：从合同归属部门沿 parentId 向上递归，父链（含自身）命中当前用户部门才放行。
     * 在 HTTP 线程中调用（依赖 LoginHelper 获取当前用户）。
     */
    void checkContractDeptScope(Long contractDeptId) {
        checkContractDeptScope(contractDeptId, loadDeptParentMap());
    }

    /**
     * 门店数据权限校验（批量场景复用同一份部门父链映射，避免逐单查库）。
     */
    void checkContractDeptScope(Long contractDeptId, Map<Long, Long> deptParentMap) {
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        if (contractDeptId == null) {
            throw new ServiceException("该合同无归属门店，无法发起结佣");
        }
        Long myDeptId = LoginHelper.getDeptId();
        if (myDeptId == null) {
            throw new ServiceException("当前用户无归属门店，无法发起结佣");
        }
        // 从合同归属部门开始沿父链向上找：命中用户部门（含恰好同级）即放行
        Long currentDeptId = contractDeptId;
        while (currentDeptId != null) {
            if (myDeptId.equals(currentDeptId)) {
                return;
            }
            Long parentId = deptParentMap == null ? null : deptParentMap.get(currentDeptId);
            // parentId 为 0（RuoYi 虚拟根）、缺失或自引用时终止，避免死循环
            if (parentId == null || parentId == 0L || parentId.equals(currentDeptId)) {
                break;
            }
            currentDeptId = parentId;
        }
        throw new ServiceException("无权发起该门店的合同结佣");
    }

    /**
     * 加载正常状态部门的 deptId → parentId 映射，用于沿父链向上做归属校验。
     */
    private Map<Long, Long> loadDeptParentMap() {
        List<DeptDTO> depts = deptService.selectDeptsByList();
        Map<Long, Long> parentMap = new HashMap<>();
        if (depts != null) {
            for (DeptDTO dept : depts) {
                if (dept.getDeptId() != null) {
                    parentMap.put(dept.getDeptId(), dept.getParentId());
                }
            }
        }
        return parentMap;
    }

    /**
     * 查合同实收事实的门店 ID（取首条事实的 deptId）。
     */
    Long resolveContractDeptId(String period, String contractNo) {
        List<PerformanceFactSummaryDTO> facts = performanceQueryPort
            .findActiveByContract(period, contractNo, FACT_TYPE_REAL);
        return facts.stream()
            .map(PerformanceFactSummaryDTO::getDeptId)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    /**
     * 发起结佣并提交审批（拉取该合同当月事实 → 生成明细 → 立即提交进入审批流，§4.1/§3.1）。
     * <p>
     * 业务人员一次操作即完成发起+提交，无需再单独点"提交"按钮。
     * 兼容驳回重提：该合同当月已有 REJECTED 单时，直接重新提交该单进入审批流，不新建单。
     * 新流程前置（§3.2）：合同实收事实必须已完成实收业绩审批（received APPROVED），否则拒绝。
     *
     * @param period     业绩归属月（结算月 YYYY-MM）
     * @param contractNo 合同号
     * @param operatorId 发起人 ID
     * @return 申请单（已提交，进入审批流）
     */
    @Transactional(rollbackFor = Exception.class)
    public CommissionApplication apply(String period, String contractNo, Long operatorId) {
        if (StringUtils.isBlank(period) || StringUtils.isBlank(contractNo)) {
            throw new ServiceException("结算月与合同号不能为空");
        }
        checkPeriodOpen(period, "发起结佣");
        // 门店数据权限校验：非超管只能发起自己门店（含下级）的合同
        Long contractDeptId = resolveContractDeptId(period, contractNo);
        checkContractDeptScope(contractDeptId);
        // 驳回单重提：该合同当月已有 REJECTED 单时，直接重新提交，不新建单
        CommissionApplication rejected = findRejectedApplication(period, contractNo);
        if (rejected != null) {
            submit(rejected.getId(), operatorId);
            return rejected;
        }
        CommissionApplication application = doApply(period, contractNo, operatorId);
        submit(application.getId(), operatorId);
        return application;
    }

    /**
     * 按合同号批量发起结佣（CompletableFuture 挂起等待，线程池逐单处理）。
     * <p>去重合同号，逐张发起并提交审批。已有未完结单（DRAFT/SUBMITTED/APPROVED/LOCKED）跳过；
     * REJECTED 单自动重提。单合同失败不阻断整批。
     *
     * @param period      业绩归属月
     * @param contractNos 合同号列表（允许重复，内部去重）
     * @param operatorId  发起人 ID
     * @return 批量发起结果
     */
    public CompletableFuture<BatchResultDTO> batchApplyByContract(
            String period, List<String> contractNos, Long operatorId) {
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
        checkPeriodOpen(period, "批量发起结佣");
        // 同步阶段过滤：在 HTTP 线程中有 Sa-Token 上下文，校验门店权限
        // 非超管用户只能发起归属部门在本部门（含本部门下级）链路上的合同，无权的直接计入跳过；
        // 部门父链映射只加载一次，逐单沿父链向上校验
        Map<Long, Long> deptParentMap = LoginHelper.isSuperAdmin() ? null : loadDeptParentMap();
        LinkedHashSet<String> myContracts = new LinkedHashSet<>();
        BatchResultDTO syncResult = new BatchResultDTO();
        syncResult.setTotal(deduped.size());
        for (String contractNo : deduped) {
            try {
                Long contractDeptId = resolveContractDeptId(period, contractNo);
                if (contractDeptId == null) {
                    syncResult.getSkippedContracts().add(contractNo);
                    continue;
                }
                checkContractDeptScope(contractDeptId, deptParentMap);
                myContracts.add(contractNo);
            } catch (ServiceException e) {
                syncResult.getSkippedContracts().add(contractNo);
                log.warn("[结佣-批量发起] 合同 {} 门店权限校验失败：{}", contractNo, e.getMessage());
            }
        }
        syncResult.setSkipped(syncResult.getSkippedContracts().size());
        log.info("[结佣-批量发起] period={}, total={}, myContracts={}, skipped={}, operator={}",
            period, deduped.size(), myContracts.size(), syncResult.getSkipped(), operatorId);
        final BatchResultDTO preResult = syncResult;
        return CompletableFuture.supplyAsync(
            () -> {
                BatchResultDTO asyncResult = doBatchApply(period, myContracts, operatorId);
                preResult.getSkippedContracts().forEach(asyncResult.getSkippedContracts()::add);
                asyncResult.setTotal(preResult.getTotal());
                asyncResult.setSkipped(asyncResult.getSkippedContracts().size());
                return asyncResult;
            }, taskExecutor);
    }

    /**
     * 逐张发起（线程池执行，CompletableFuture 供应方）。
     * 通过 SpringUtils.getBean 走代理调 apply，确保 @Transactional 生效。
     * 异步线程无 Sa-Token 上下文，submit 内 currentRoles() 返回空集 →
     * 跳过总监自动审批节点（总监可后续批量审批），其余逻辑正常执行。
     */
    private BatchResultDTO doBatchApply(String period, LinkedHashSet<String> contractNos, Long operatorId) {
        CommissionApplicationService self = SpringUtils.getBean(CommissionApplicationService.class);
        BatchResultDTO result = new BatchResultDTO();
        result.setTotal(contractNos.size());
        for (String contractNo : contractNos) {
            try {
                CommissionApplication existing = findActiveApplication(period, contractNo);
                if (existing != null && existing.getStatus() != ApplicationStatus.REJECTED) {
                    result.getSkippedContracts().add(contractNo);
                    continue;
                }
                self.apply(period, contractNo, operatorId);
                result.getSuccessContracts().add(contractNo);
            } catch (Exception e) {
                result.getFailedContracts().add(contractNo);
                log.warn("[结佣-批量发起] 合同 {} 发起失败：{}", contractNo, e.getMessage());
            }
        }
        result.setSuccess(result.getSuccessContracts().size());
        result.setSkipped(result.getSkippedContracts().size());
        result.setFailed(result.getFailedContracts().size());
        log.info("[结佣-批量发起] period={}, 成功={}, 跳过={}, 失败={}",
            period, result.getSuccess(), result.getSkipped(), result.getFailed());
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
        // 存事实中的真实合同号（用户可能输入订单号，需归一化为 contract_no）
        application.setContractNo(first.getContractNo() != null ? first.getContractNo() : contractNo);
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
     * 实收/应收差异容忍阈值（元），从系统参数 {@code panjia.commission.diff_tolerance} 读取，
     * 缺失时回退默认值 1 元。
     */
    private BigDecimal getDiffTolerance() {
        BigDecimal v = configService.getConfigDecimal(CONFIG_DIFF_TOLERANCE);
        return v == null ? DEFAULT_DIFF_TOLERANCE : v;
    }

    /**
     * 实收/应收差异容忍判定：|a - b| <= 容忍阈值（默认 1 元）视为无差异。
     * <p>用于：① 互斥网关是否跳过财务节点；② 是否触发实收对齐应收；③ 前端「有差异」标签展示。
     * 容忍范围内的小额尾差不做对齐，保持实收原样。阈值可在系统参数中调整，无需改代码。</p>
     */
    public boolean isWithinTolerance(BigDecimal a, BigDecimal b) {
        BigDecimal av = a == null ? BigDecimal.ZERO : a;
        BigDecimal bv = b == null ? BigDecimal.ZERO : b;
        return av.subtract(bv).abs().compareTo(getDiffTolerance()) <= 0;
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
            Long taskId = approvalPort.currentTaskId(BizType.COMMISSION, applicationId);
            if (taskId == null) {
                throw new ServiceException("审批流程任务不存在，请联系管理员");
            }
            approvalPort.completeAsSys(BizType.COMMISSION, applicationId, ApprovalAction.PASS, "重新提交");
        }

        // 发起人=总监（或超管）→ 以登录人身份办理总监节点（§3.1 总监发起）；
        // 后续 §3.5 差异对齐与「无差异自动过财务」由 capp_finance 任务创建事件监听器接管
        if (currentRoles().contains(ROLE_DIRECTOR)) {
            Long directorTask = taskAtNode(applicationId, NODE_DIRECTOR);
            if (directorTask == null) {
                throw new ServiceException("总监发起后未停留在总监审批节点，请联系管理员");
            }
            completeTaskAsLoginUser(applicationId, ApprovalAction.PASS, "总监发起，系统自动审批");
        }
        refreshCurrentNode(application);
        log.info("[结佣-提交] 合同申请单已提交：applyNo={}, contractNo={}, operator={}, node={}",
            application.getApplyNo(), application.getContractNo(), operatorId, application.getCurrentNode());
    }

    /**
     * 单个审批通过（§3.3，供 Excel 批量审批复用）：办理当前待办任务，节点鉴权完全交给流程引擎。
     * <p>
     * 引擎按 flow_user 名单判权（越权直接拒绝）；总监节点办理后的 §3.5 实收对齐与
     * 「无差异自动过财务」由 {@code CommissionApplyWorkflowListener} 的 capp_finance
     * 任务创建事件接管，无论总监从「我的待办」还是业务页通过都会触发。
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long applicationId, ApprovalAction action, String comment) {
        CommissionApplication application = requireSubmitted(applicationId);
        String node = approvalPort.currentNodeCode(BizType.COMMISSION, applicationId);
        if (!NODE_DIRECTOR.equals(node) && !NODE_FINANCE.equals(node)) {
            throw new ServiceException("当前无可审批节点（节点=" + node + "）");
        }
        Long taskId = approvalPort.currentTaskId(BizType.COMMISSION, applicationId);
        if (taskId == null) {
            throw new ServiceException("当前无待办任务");
        }
        // T-04：总监 PASS 前更新流程变量 realAmount/expectedAmount 为最新值，
        // 互斥网关按 eq@@${realAmount}@@${expectedAmount} 求值决定是否跳过财务。
        // REJECT 不触发互斥网关，无需更新金额变量。
        if (NODE_DIRECTOR.equals(node) && action == ApprovalAction.PASS) {
            updateAmountVariables(application);
        }
        String defaultComment = action == ApprovalAction.PASS
            ? (NODE_DIRECTOR.equals(node) ? "总监审批通过" : "财务审批通过")
            : (NODE_DIRECTOR.equals(node) ? "总监审批驳回" : "财务审批驳回");
        String message = StringUtils.isBlank(comment) ? defaultComment : comment;
        completeTaskAsLoginUser(applicationId, action, message);
        refreshCurrentNode(application);
    }

    /**
     * 便捷方法：默认 PASS 通过，无意见。
     * <p>批量审批 / 内部自动审批场景使用，保持向后兼容。
     */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long applicationId) {
        approve(applicationId, ApprovalAction.PASS, null);
    }

    /**
     * 更新流程变量 realAmount / expectedAmount（供互斥网关 skip_condition 求值，T-04）。
     * <p>当配置开关 {@code panjia.commission.skip_finance_when_match} 关闭时，
     * 故意将 realAmount 设为与 expectedAmount 不同的值，使条件线不命中，
     * 流程走默认分支进财务人工审批。</p>
     */
    private void updateAmountVariables(CommissionApplication application) {
        boolean skipEnabled = Boolean.TRUE.equals(
            configService.getConfigBool(CONFIG_SKIP_FINANCE_WHEN_MATCH));
        BigDecimal realAmount = application.getTotalAmount() == null
            ? BigDecimal.ZERO : application.getTotalAmount();
        BigDecimal expectedAmount = application.getExpectedAmount() == null
            ? BigDecimal.ZERO : application.getExpectedAmount();
        if (!skipEnabled) {
            // 开关关闭：故意写入不相等的值，条件线不命中，走财务节点
            realAmount = expectedAmount.add(BigDecimal.ONE);
        } else if (isWithinTolerance(realAmount, expectedAmount)) {
            // 差异在容忍阈值（1 元）以内：视为无差异，令网关 eq 命中跳过财务节点
            realAmount = expectedAmount;
        }
        Map<String, Object> vars = new HashMap<>(2);
        vars.put("realAmount", realAmount);
        vars.put("expectedAmount", expectedAmount);
        approvalPort.setVariable(BizType.COMMISSION, application.getId(), vars);
    }

    /**
     * 以当前登录人身份办理任务（不忽略权限）。
     * <p>越权时流程引擎抛 {@code NULL_ROLE_NODE}（"无法跳转到该节点,请检查当前用户是否有权限!"），
     * 此处转为业务可读提示；其余异常原样抛出，避免掩盖真实故障。</p>
     */
    private void completeTaskAsLoginUser(Long applicationId, ApprovalAction action, String message) {
        // 平台约定：超管等同系统身份（原生 TaskOpPrepareComponent 亦对超管置 ignore），
        // 保留其运维解卡能力；除此之外的所有业务角色一律走引擎原生鉴权。
        if (LoginHelper.isSuperAdmin()) {
            approvalPort.completeAsSys(BizType.COMMISSION, applicationId, action, message);
            return;
        }
        try {
            approvalPort.complete(BizType.COMMISSION, applicationId, action, message);
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("请检查当前用户是否有权限") || msg.contains("无法跳转到该节点")) {
                throw new ServiceException("您不是该单据当前审批节点的办理人，无权审批", e);
            }
            throw e;
        }
    }

    /**
     * 按合同号批量审批（CompletableFuture 挂起等待，线程池逐单办理当前待办节点）。
     * <p>去重合同号，非 SUBMITTED 或无待办任务的跳过。单合同失败不阻断整批。
     * 异步线程用 completeTaskAsLoginUser 走引擎原生鉴权。
     *
     * @param period      结算月
     * @param contractNos 合同号列表（允许重复，内部去重）
     * @param operatorId  操作人 ID（异步线程无 Sa-Token 上下文，同步阶段捕获）
     * @return 批量审批结果
     */
    public CompletableFuture<BatchResultDTO> batchApproveByContract(
            String period, List<String> contractNos, Long operatorId) {
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
        // 同步阶段过滤：在 HTTP 线程中有 Sa-Token 上下文，用 isMyTask 检查权限
        // 只把当前用户有权办理的单子提交给异步线程，避免越权审批
        LinkedHashSet<String> myTasks = new LinkedHashSet<>();
        BatchResultDTO syncResult = new BatchResultDTO();
        syncResult.setTotal(deduped.size());
        for (String contractNo : deduped) {
            try {
                CommissionApplication application = applicationMapper.selectOne(
                    new LambdaQueryWrapper<CommissionApplication>()
                        .eq(CommissionApplication::getPeriod, period)
                        .and(w -> w.eq(CommissionApplication::getContractNo, contractNo)
                            .or().eq(CommissionApplication::getOrderNo, contractNo))
                        .eq(CommissionApplication::getStatus, ApplicationStatus.SUBMITTED)
                        .orderByDesc(CommissionApplication::getId)
                        .last("LIMIT 1"));
                if (application == null) {
                    syncResult.getSkippedContracts().add(contractNo);
                    continue;
                }
                if (!approvalPort.isMyTask(BizType.COMMISSION, application.getId())) {
                    syncResult.getSkippedContracts().add(contractNo);
                    continue;
                }
                myTasks.add(contractNo);
            } catch (Exception e) {
                syncResult.getFailedContracts().add(contractNo);
                log.warn("[结佣-批量审批] 预检失败：contractNo={}, reason={}", contractNo, e.getMessage());
            }
        }
        syncResult.setSkipped(syncResult.getSkippedContracts().size());
        syncResult.setFailed(syncResult.getFailedContracts().size());
        log.info("[结佣-批量审批] period={}, total={}, myTasks={}, skipped={}, operator={}",
            period, deduped.size(), myTasks.size(), syncResult.getSkipped(), operatorId);
        final BatchResultDTO preResult = syncResult;
        return CompletableFuture.supplyAsync(
            () -> {
                BatchResultDTO asyncResult = doBatchApprove(period, myTasks);
                preResult.getSkippedContracts().forEach(asyncResult.getSkippedContracts()::add);
                preResult.getFailedContracts().forEach(asyncResult.getFailedContracts()::add);
                asyncResult.setTotal(preResult.getTotal());
                asyncResult.setSkipped(asyncResult.getSkippedContracts().size());
                asyncResult.setFailed(asyncResult.getFailedContracts().size());
                return asyncResult;
            }, taskExecutor);
    }

    /**
     * 逐单审批（线程池执行，CompletableFuture 供应方）。
     * 异步线程无 Sa-Token 上下文，用 completeAsSys（ignore=true）办理，
     * 权限由 @SaCheckPermission 前置保障。
     */
    private BatchResultDTO doBatchApprove(String period, LinkedHashSet<String> contractNos) {
        BatchResultDTO result = new BatchResultDTO();
        result.setTotal(contractNos.size());
        for (String contractNo : contractNos) {
            try {
                CommissionApplication application = applicationMapper.selectOne(
                    new LambdaQueryWrapper<CommissionApplication>()
                        .eq(CommissionApplication::getPeriod, period)
                        .and(w -> w.eq(CommissionApplication::getContractNo, contractNo)
                            .or().eq(CommissionApplication::getOrderNo, contractNo))
                        .eq(CommissionApplication::getStatus, ApplicationStatus.SUBMITTED)
                        .orderByDesc(CommissionApplication::getId)
                        .last("LIMIT 1"));
                if (application == null) {
                    result.getSkippedContracts().add(contractNo);
                    continue;
                }
                String node = approvalPort.currentNodeCode(BizType.COMMISSION, application.getId());
                if (!NODE_DIRECTOR.equals(node) && !NODE_FINANCE.equals(node)) {
                    result.getSkippedContracts().add(contractNo);
                    continue;
                }
                if (NODE_DIRECTOR.equals(node)) {
                    updateAmountVariables(application);
                }
                String defaultComment = NODE_DIRECTOR.equals(node) ? "总监审批通过" : "财务审批通过";
                approvalPort.completeAsSys(BizType.COMMISSION, application.getId(),
                    ApprovalAction.PASS, "批量审批：" + defaultComment);
                refreshCurrentNode(application);
                result.getSuccessContracts().add(contractNo);
            } catch (Exception e) {
                result.getFailedContracts().add(contractNo);
                log.warn("[结佣-批量审批] 合同 {} 审批失败：{}", contractNo, e.getMessage());
            }
        }
        result.setSuccess(result.getSuccessContracts().size());
        result.setSkipped(result.getSkippedContracts().size());
        result.setFailed(result.getFailedContracts().size());
        log.info("[结佣-批量审批] period={}, 成功={}, 跳过={}, 失败={}",
            period, result.getSuccess(), result.getSkipped(), result.getFailed());
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
            approvalPort.cancel(BizType.COMMISSION, applicationId);
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
        // 同时按 contractNo 和 orderNo 建索引，支持一手房/房产金融/家装荐客以订单号为准
        Map<String, CommissionApplication> appMap = new LinkedHashMap<>();
        for (CommissionApplication app : applications) {
            if (StringUtils.isNotBlank(app.getContractNo())) {
                appMap.putIfAbsent(app.getContractNo(), app);
            }
            if (StringUtils.isNotBlank(app.getOrderNo())) {
                appMap.putIfAbsent(app.getOrderNo(), app);
            }
        }

        String keyword = StringUtils.trimToNull(query.getKeyword());
        // 审批节点数据隔离：审批中单据仅本人角色对应节点可见（财务→FINANCE，总监→DIRECTOR），超管看全部
        boolean nodeScopeAll = LoginHelper.isSuperAdmin();
        Set<String> myNodes = nodeScopeAll ? Set.of() : currentApprovalNodes();

        List<CommissionContractVO> all = new ArrayList<>(contracts.size());
        for (PerformanceContractSummaryDTO c : contracts) {
            CommissionApplication app = appMap.get(c.getContractNo());
            if (app == null && StringUtils.isNotBlank(c.getOrderNo())) {
                app = appMap.get(c.getOrderNo());
            }
            String status = app != null && app.getStatus() != null ? app.getStatus().getCode() : ROW_STATUS_NONE;
            if (app == null && !"APPROVED".equals(c.getReceivedStatus())) {
                continue;
            }
            if (StringUtils.isNotBlank(query.getStatus()) && !query.getStatus().equals(status)) {
                continue;
            }
            // 审批节点数据隔离：审批中（SUBMITTED）单据仅本人角色对应节点可见；非审批中/未发起单据不受限
            if (!nodeScopeAll && ApplicationStatus.SUBMITTED.getCode().equals(status)
                && (app == null || !myNodes.contains(app.getCurrentNode()))) {
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
            // 应收展示当前 ACTIVE 值（含已生效调整），与快照不一致时标「已调整」
            if (app.getExpectedAmount() != null && c.getExpectedAmount() != null
                && app.getExpectedAmount().compareTo(c.getExpectedAmount()) != 0) {
                vo.setExpectedAdjusted(true);
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
        // 应收展示当前 ACTIVE 值（含已生效调整），与快照不一致时标「已调整」
        fillExpectedAdjusted(application);
        return application;
    }

    /** 按申请单 ID 查流程实例 ID */
    public Long getInstanceId(Long applicationId) {
        return approvalPort.instanceId(BizType.COMMISSION, applicationId);
    }

    /**
     * 查当前 ACTIVE PERF_EXPECT 合计，与申请单快照比较：
     * 不一致时置 expectedAdjusted=true，并用当前值覆盖 expectedAmount 供前端展示。
     */
    private void fillExpectedAdjusted(CommissionApplication app) {
        if (app == null || StringUtils.isBlank(app.getPeriod())
            || (StringUtils.isBlank(app.getContractNo()) && StringUtils.isBlank(app.getOrderNo()))) {
            return;
        }
        String lookupKey = StringUtils.isNotBlank(app.getContractNo()) ? app.getContractNo() : app.getOrderNo();
        List<PerformanceFactSummaryDTO> expectFacts =
            performanceQueryPort.findActiveByContract(app.getPeriod(), lookupKey, FACT_TYPE_EXPECT);
        BigDecimal currentExpected = expectFacts.stream()
            .map(PerformanceFactSummaryDTO::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (app.getExpectedAmount() != null && app.getExpectedAmount().compareTo(currentExpected) != 0) {
            app.setExpectedAdjusted(true);
        }
        app.setExpectedAmount(currentExpected);
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
     * 构建审批启动命令（业务编码/标题 + 流程变量），供适配器转译为引擎原生 StartProcessDTO + bizExt。
     * <p>
     * 不填的后果：flow_instance_biz_ext.business_title 为空，待办列表业务编码/业务标题两列全空，
     * 审批人只能看到一串技术编码，无法分辨审的是哪张单。
     */
    private ApprovalStartCmd buildStartCmd(CommissionApplication application) {
        ApprovalStartCmd cmd = ApprovalStartCmd.of(
            text(application.getApplyNo()),
            "结佣审批｜" + text(application.getContractNo())
                + " " + text(application.getPropertyAddress())
                + "｜账期" + text(application.getPeriod())
                + "｜应收" + text(application.getTotalAmount()));
        Map<String, Object> variables = new HashMap<>(4);
        variables.put("ignore", true);
        // T-04：发起流程时写入 realAmount/expectedAmount 初值，供互斥网关 skip_condition 求值；
        // 总监办理前 approve() 会再次更新为最新值。差异容忍（≤1 元）逻辑与 updateAmountVariables 保持一致，
        // 以覆盖总监发起时系统自动过总监节点的场景（该路径不经 approve，不会再次修改变量）。
        BigDecimal realAmount = application.getTotalAmount() == null
            ? BigDecimal.ZERO : application.getTotalAmount();
        BigDecimal expectedAmount = application.getExpectedAmount() == null
            ? BigDecimal.ZERO : application.getExpectedAmount();
        boolean skipEnabled = Boolean.TRUE.equals(
            configService.getConfigBool(CONFIG_SKIP_FINANCE_WHEN_MATCH));
        if (!skipEnabled) {
            realAmount = expectedAmount.add(BigDecimal.ONE);
        } else if (isWithinTolerance(realAmount, expectedAmount)) {
            realAmount = expectedAmount;
        }
        variables.put("realAmount", realAmount);
        variables.put("expectedAmount", expectedAmount);
        cmd.setVariables(variables);
        return cmd;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 启动 commission_apply 流程并办理申请人首节点。
     */
    private void startWorkflow(CommissionApplication application) {
        ApprovalStartCmd cmd = buildStartCmd(application);
        try {
            boolean ok = approvalPort.startAndCompleteFirst(BizType.COMMISSION, application.getId(), cmd);
            if (!ok) {
                throw new ServiceException("结佣审批流程发起失败");
            }
        } catch (Exception e) {
            log.error("[结佣] 流程发起异常：id={}", application.getId(), e);
            throw new ServiceException("结佣审批流程发起失败：{}", e.getMessage());
        }
        Long instanceId = approvalPort.instanceId(BizType.COMMISSION, application.getId());
        if (instanceId != null) {
            application.setProcessInstanceId(String.valueOf(instanceId));
            applicationMapper.updateById(application);
        }
    }

    /**
     * 总监节点办理完成后的联动（§3.5 实收对齐，由 capp_finance 任务创建事件驱动）。
     * <p>
     * T-04 改造后：实收==应收时互斥网关 skip_condition 直接跳到 capp_end，
     * 财务节点不创建，本方法不触发；本方法仅在「有差异进入财务节点」时执行实收对齐。
     * </p>
     * <ol>
     *   <li>比对单内实收合计与应收合计：有差异且未对齐 → 调业绩域对齐端口，
     *       实收事实（合同+每人明细）supersede 为应收口径，结佣明细按映射重绑事实+金额并重算；</li>
     *   <li>对齐后停留财务节点，由财务人工审批（网关已路由到 capp_finance，不再旁路 completeAsSys）。</li>
     * </ol>
     * <p>监听器在总监 completeTask 的事务内同步执行；warm-flow 引擎在进入监听前已完成
     * 任务持久化。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void afterDirectorPassed(Long applicationId, Long operatorId) {
        CommissionApplication application = applicationMapper.selectById(applicationId);
        if (application == null) {
            log.warn("[结佣-总监通过联动] 申请单不存在，忽略：id={}", applicationId);
            return;
        }
        BigDecimal received = application.getTotalAmount() == null ? BigDecimal.ZERO : application.getTotalAmount();
        BigDecimal expected = application.getExpectedAmount() == null
            ? BigDecimal.ZERO : application.getExpectedAmount();
        // 差异在容忍阈值（1 元）以内视为无差异，不触发实收对齐应收，保持实收原样
        boolean hasDiff = !isWithinTolerance(received, expected);

        if (hasDiff && !Boolean.TRUE.equals(application.getAligned())) {
            log.info("[结佣-对齐] 实收与应收存在差异，触发自动对齐：id={}, received={}, expected={}",
                application.getId(), received, expected);
            ReceivedAlignmentResultDTO result = performanceQueryPort.alignReceivedToExpected(
                application.getPeriod(), application.getContractNo(), operatorId);
            rebindItemsAfterAlignment(application, result);
            application.setAligned(true);
            recalcAggregates(application.getId(), application);
        }
        // T-04：不再调 completeAsSys 旁路完成财务节点——交由互斥网关 skip_condition 决定路由
        refreshCurrentNode(application);
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
        String current = approvalPort.currentNodeCode(BizType.COMMISSION, applicationId);
        return nodeCode.equals(current) ? approvalPort.currentTaskId(BizType.COMMISSION, applicationId) : null;
    }

    /** 从工作流回写当前节点（capp_director→DIRECTOR / capp_finance→FINANCE / 已结束→null）。
     * <p>只更新 current_node 列：事件监听器可能在嵌套 completeTask 内被触发，
     * 此时持有的实体已过期（状态/版本已被 finish 回调推进），整实体 updateById 会静默失败或回写脏状态。</p> */
    private void refreshCurrentNode(CommissionApplication application) {
        String nodeCode = approvalPort.currentNodeCode(BizType.COMMISSION, application.getId());
        String shortNode;
        if (NODE_DIRECTOR.equals(nodeCode)) {
            shortNode = "DIRECTOR";
        } else if (NODE_FINANCE.equals(nodeCode)) {
            shortNode = "FINANCE";
        } else {
            shortNode = null;
        }
        application.setCurrentNode(shortNode);
        applicationMapper.update(null, new LambdaUpdateWrapper<CommissionApplication>()
            .eq(CommissionApplication::getId, application.getId())
            .set(CommissionApplication::getCurrentNode, shortNode));
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
            .and(w -> w.eq(CommissionApplication::getContractNo, contractNo)
                .or().eq(CommissionApplication::getOrderNo, contractNo))
            .in(CommissionApplication::getStatus, ApplicationStatus.DRAFT, ApplicationStatus.SUBMITTED,
                ApplicationStatus.APPROVED, ApplicationStatus.LOCKED)
            .orderByDesc(CommissionApplication::getCreateTime)
            .last("LIMIT 1"));
    }

    /**
     * 查询该合同当月最近一张驳回单（REJECTED），供 add 兼容驳回重提使用。
     * 多张驳回单取最新一张，其余保留不动。
     */
    private CommissionApplication findRejectedApplication(String period, String contractNo) {
        return applicationMapper.selectOne(new LambdaQueryWrapper<CommissionApplication>()
            .eq(CommissionApplication::getPeriod, period)
            .and(w -> w.eq(CommissionApplication::getContractNo, contractNo)
                .or().eq(CommissionApplication::getOrderNo, contractNo))
            .eq(CommissionApplication::getStatus, ApplicationStatus.REJECTED)
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

    /**
     * 当前登录用户可见的审批节点短码集合（列表数据隔离用）：
     * 财务角色 → FINANCE，总监角色 → DIRECTOR，可兼有；无审批角色返回空集。
     * 超管不经过此判定（调用方直接放行全部）。
     */
    private Set<String> currentApprovalNodes() {
        Set<String> roles = currentRoles();
        Set<String> nodes = new HashSet<>();
        if (roles.contains("finance")) {
            nodes.add("FINANCE");
        }
        if (roles.contains(ROLE_DIRECTOR)) {
            nodes.add("DIRECTOR");
        }
        return nodes;
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
