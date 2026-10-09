package com.panjia.commission.service;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.mock.SaRequestForMock;
import cn.dev33.satoken.context.mock.SaResponseForMock;
import cn.dev33.satoken.context.mock.SaStorageForMock;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.panjia.common.util.DeptScopeUtils;
import com.panjia.commission.domain.ApplicationStatus;
import com.panjia.commission.domain.AdjustStatus;
import com.panjia.commission.domain.AdjustType;
import com.panjia.commission.domain.CommissionAdjust;
import com.panjia.commission.domain.CommissionApplication;
import com.panjia.commission.domain.CommissionConsumeLog;
import com.panjia.commission.domain.CommissionItem;
import com.panjia.commission.domain.ItemStatus;
import com.panjia.commission.domain.ReversedReason;
import com.panjia.commission.domain.bo.CommissionAdjustPayload;
import com.panjia.commission.domain.bo.CommissionApplyBo;
import com.panjia.commission.domain.vo.CommissionBatchResultVo;
import com.panjia.commission.domain.vo.CommissionContractVo;
import com.panjia.commission.domain.vo.CommissionItemDetailVo;
import com.panjia.commission.mapper.CommissionAdjustMapper;
import com.panjia.commission.mapper.CommissionApplicationMapper;
import com.panjia.commission.mapper.CommissionConsumeLogMapper;
import com.panjia.commission.mapper.CommissionItemMapper;
import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.dto.PerformanceContractSummaryDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.dto.ReceivedAlignmentResultDTO;
import com.panjia.contracts.event.CommissionApprovedEvent;
import com.panjia.contracts.event.EventPort;
import com.panjia.contracts.port.ApprovalAction;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ApprovalStartCmd;
import com.panjia.contracts.port.CommissionPerformanceQueryPort;
import com.panjia.contracts.port.ConversionFactorPort;
import com.panjia.contracts.port.MyTaskBrief;
import com.panjia.contracts.port.PeriodCloseQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private final CommissionApplicationMapper applicationMapper;
    private final CommissionItemMapper itemMapper;
    private final CommissionConsumeLogMapper consumeLogMapper;
    /** 调整单 mapper：在途调整预演（列表/详情「调整审批中」标记与金额预览） */
    private final CommissionAdjustMapper adjustMapper;
    /** 折算比例唯一来源：契约层端口（规则表由薪酬域持有，本域不直连） */
    private final ConversionFactorPort conversionFactorPort;
    private final CommissionPerformanceQueryPort performanceQueryPort;
    private final PeriodCloseQueryPort periodCloseQueryPort;
    private final EventPort eventPort;
    private final ApprovalPort approvalPort;
    private final ConfigService configService;
    private final DeptService deptService;
    private final TaskExecutor taskExecutor;

    /**
     * 批量发起上下文：承载在批量入口预加载的数据，贯穿 doBatchApply → apply →
     * createApplicationWithItems 调用链，避免逐合同重复查询（N 次全表/单行查询→1 次批量查询）。
     * <ul>
     *   <li>{@code factsMap}：同步阶段已查的实收事实，建单直接复用</li>
     *   <li>{@code expectedAmounts}：全期间合同汇总一次性加载，建单按 contractNo 取值</li>
     *   <li>{@code activeApps} / {@code rejectedApps}：一条 IN 查询取所有合同当月申请单</li>
     * </ul>
     * 单合同发起路径 ctx=null，走原有逐单查询逻辑。
     */
    private static class BatchApplyContext {
        /** 输入合同号/订单号 → 实收事实列表（同步阶段查到，异步复用） */
        final Map<String, List<PerformanceFactSummaryDTO>> factsMap = new HashMap<>();
        /** 合同号 → 应收金额（全期间一次加载） */
        final Map<String, BigDecimal> expectedAmounts = new HashMap<>();
        /** 输入字符串 → 活跃申请单（DRAFT/SUBMITTED/APPROVED/LOCKED） */
        final Map<String, CommissionApplication> activeApps = new HashMap<>();
        /** 输入字符串 → 驳回申请单（REJECTED） */
        final Map<String, CommissionApplication> rejectedApps = new HashMap<>();
    }

    /**
     * 批量审批预检结果项：同步阶段已定位申请单 + 当前用户可办任务，
     * 异步办理直接用 taskId，无需重复查申请单/当前任务/节点。
     */
    private static class BatchApproveItem {
        /** 用户输入的合同号或订单号 */
        final String contractNo;
        final CommissionApplication application;
        final MyTaskBrief task;

        BatchApproveItem(String contractNo, CommissionApplication application, MyTaskBrief task) {
            this.contractNo = contractNo;
            this.application = application;
            this.task = task;
        }
    }

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
     * 查合同归属门店 ID（取该合同 ACTIVE 新签事实的首条 deptId）。
     * 实收仅为门控，部门归属以新签事实为准。
     */
    Long resolveContractDeptId(String period, String contractNo) {
        List<PerformanceFactSummaryDTO> facts = performanceQueryPort
            .findActiveByContract(period, contractNo, FACT_TYPE_EXPECT);
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
        return apply(period, contractNo, operatorId, false);
    }

    /**
     * 发起结佣并提交审批。
     *
     * @param skipDeptScope 是否跳过门店数据权限校验。批量发起时门店权限已在 HTTP 线程的同步阶段
     *                      逐个校验过；异步线程无 Sa-Token 上下文，而门店校验查询的部门表带
     *                      {@code @DataPermission}，拦截器取登录态会抛 SaTokenContextException，
     *                      故异步路径必须跳过（不构成越权：同步阶段已过滤）。
     */
    @Transactional(rollbackFor = Exception.class)
    public CommissionApplication apply(String period, String contractNo, Long operatorId, boolean skipDeptScope) {
        return apply(period, contractNo, operatorId, skipDeptScope, null);
    }

    /**
     * 发起结佣并提交审批。
     *
     * @param skipDeptScope 是否跳过门店数据权限校验。批量发起时门店权限已在 HTTP 线程的同步阶段
     *                      逐个校验过；异步线程无 Sa-Token 上下文，而门店校验查询的部门表带
     *                      {@code @DataPermission}，拦截器取登录态会抛 SaTokenContextException，
     *                      故异步路径必须跳过（不构成越权：同步阶段已过滤）。
     * @param ctx           批量上下文（预加载的应收/事实/申请单），null 时走单合同路径逐单查；
     *                      非 null 时跳过 checkPeriodOpen（批量入口已查）、复用预加载的驳回单和事实
     */
    @Transactional(rollbackFor = Exception.class)
    public CommissionApplication apply(String period, String contractNo, Long operatorId,
                                       boolean skipDeptScope, BatchApplyContext ctx) {
        if (StringUtils.isBlank(period) || StringUtils.isBlank(contractNo)) {
            throw new ServiceException("结算月与合同号不能为空");
        }
        // 批量入口已查过封账状态，跳过（P1：N→1 次）
        if (ctx == null) {
            checkPeriodOpen(period, "发起结佣");
        }
        // 门店数据权限校验：非超管只能发起自己门店（含下级）的合同
        if (!skipDeptScope) {
            Long contractDeptId = resolveContractDeptId(period, contractNo);
            checkContractDeptScope(contractDeptId);
        }
        // 驳回单重提：从 ctx 取预加载的驳回单，避免逐单查（P1）
        CommissionApplication rejected = ctx != null
            ? ctx.rejectedApps.get(contractNo)
            : findRejectedApplication(period, contractNo);
        if (rejected != null) {
            submit(rejected.getId(), operatorId);
            return rejected;
        }
        // 实收审批通过后系统自动生成的 DRAFT 草稿单（不自动提交，保留人工审批链）：
        // 人工单个/批量发起时直接提交该草稿；草稿常由导入归档操作人身份自动建立，
        // 提交时把申请人改为实际提交人（列表「申请人」、审批中数据隔离均以申请人为准）。
        // SUBMITTED/APPROVED/LOCKED 属重复发起，直接拒绝。
        CommissionApplication active = ctx != null
            ? ctx.activeApps.get(contractNo)
            : findActiveApplication(period, contractNo);
        if (active != null) {
            if (active.getStatus() == ApplicationStatus.DRAFT) {
                // 新签调整（加人/金额变更）会 supersede 旧事实 → FACT_REVERSED 把旧结佣明细
                // 置 REVERSED；FACT_CREATED 不重建明细（设计如此）。DRAFT 单明细可能全部被冲销，
                // 提交前需检查：没有 ACTIVE 明细则从当前新签事实重建。
                long activeItems = itemMapper.selectCount(new LambdaQueryWrapper<CommissionItem>()
                    .eq(CommissionItem::getApplicationId, active.getId())
                    .ne(CommissionItem::getStatus, ItemStatus.REVERSED));
                if (activeItems == 0) {
                    rebuildItems(active, period, contractNo);
                }
                if (!Objects.equals(active.getApplicantId(), operatorId)) {
                    active.setApplicantId(operatorId);
                    applicationMapper.updateById(active);
                }
                submit(active.getId(), operatorId);
                return active;
            }
            throw new ServiceException("合同 " + contractNo + " " + period
                + " 月已存在" + active.getStatus().getDesc() + "申请单（" + active.getApplyNo() + "），请勿重复发起");
        }
        CommissionApplication application = createApplicationWithItems(period, contractNo, operatorId, ctx);
        submit(application.getId(), operatorId);
        return application;
    }

    /**
     * 按合同号批量发起结佣（CompletableFuture 挂起等待，线程池逐单处理）。
     * <p>去重合同号，逐张发起并提交审批。已有未完结单（DRAFT/SUBMITTED/APPROVED/LOCKED）跳过；
     * REJECTED 单自动重提。单合同失败不阻断整批。
     * <p>期间口径：结佣期间由发起人在弹窗手动选择（必传 YYYY-MM），不再自动取当前月；
     * 实收事实跨期查找——6 月实收 10 月发起即归属 10 月结佣，不按实收日期定期间。
     *
     * @param period      结佣归属月（必传，发起人选择）
     * @param contractNos 合同号列表（允许重复，内部去重）
     * @param operatorId  发起人 ID
     * @return 批量发起结果
     */
    public CompletableFuture<CommissionBatchResultVo> batchApplyByContract(
            String period, List<String> contractNos, Long operatorId) {
        // 结佣期间必选：由发起人显式选择归属月，不再默认当前月
        final String applyPeriod = StringUtils.trimToNull(period);
        if (applyPeriod == null) {
            throw new ServiceException("请选择结佣期间");
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
        checkPeriodOpen(applyPeriod, "批量发起结佣");
        // 在 HTTP 线程捕获本次请求的 token：异步线程无 Sa-Token 上下文，
        // 需在线程内安装携带该 token 的 Mock 上下文（会话仍从 Redis 读取），
        // 否则流程发起人变量(initiator)为空、总监发起无法自动过总监节点、数据权限拦截报错
        String tokenName = SaManager.getConfig().getTokenName();
        String tokenValue = ServletUtils.getRequest().getHeader(tokenName);
        // 同步阶段过滤：在 HTTP 线程中有 Sa-Token 上下文，校验门店权限
        // 非超管用户只能发起归属部门在本部门（含本部门下级）链路上的合同，无权的直接计入跳过；
        // 部门父链映射只加载一次，逐单沿父链向上校验
        Map<Long, Long> deptParentMap = LoginHelper.isSuperAdmin() ? null : loadDeptParentMap();
        // 批量上下文：同步阶段缓存事实（避免 doApply 重复查）、预加载应收金额（避免逐单全表查）
        BatchApplyContext ctx = new BatchApplyContext();
        // 应收金额一次性加载：跨月口径，以用户输入的业务键直接匹配（不依赖实收期间）
        ctx.expectedAmounts.putAll(performanceQueryPort.sumExpectAmountsByKeysCrossPeriod(deduped));
        LinkedHashSet<String> myContracts = new LinkedHashSet<>();
        CommissionBatchResultVo syncResult = new CommissionBatchResultVo();
        syncResult.setTotal(deduped.size());
        for (String contractNo : deduped) {
            try {
                // 查实收事实并缓存，doApply 直接复用（P0：减少一半事实查询）；
                // 跨期查找（period 传 null）：发起月不必与实收月一致
                List<PerformanceFactSummaryDTO> facts = performanceQueryPort
                    .findActiveByContract(null, contractNo, FACT_TYPE_REAL);
                ctx.factsMap.put(contractNo, facts);
                Long contractDeptId = facts.stream()
                    .map(PerformanceFactSummaryDTO::getDeptId)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
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
            applyPeriod, deduped.size(), myContracts.size(), syncResult.getSkipped(), operatorId);
        final CommissionBatchResultVo preResult = syncResult;
        return CompletableFuture.supplyAsync(
            () -> runWithOperatorToken(tokenName, tokenValue, () -> {
                CommissionBatchResultVo asyncResult = doBatchApply(applyPeriod, myContracts, operatorId, ctx);
                preResult.getSkippedContracts().forEach(asyncResult.getSkippedContracts()::add);
                asyncResult.setTotal(preResult.getTotal());
                asyncResult.setSkipped(asyncResult.getSkippedContracts().size());
                return asyncResult;
            }), taskExecutor);
    }

    /**
     * 为异步线程安装携带操作人 token 的 Sa-Token Mock 上下文，执行完毕后清理。
     * <p>
     * 线程池线程本身无 HTTP 请求，{@code StpUtil} 取不到会话。安装后 LoginHelper、
     * 工作流发起链路（initiator/initiatorDeptId 变量、办理人）、数据权限拦截器、
     * 总监发起自动审批等行为与 HTTP 线程完全一致；会话数据仍统一从 Redis 读取，无伪造登录。
     */
    private <T> T runWithOperatorToken(String tokenName, String tokenValue, java.util.function.Supplier<T> action) {
        SaRequestForMock mockRequest = new SaRequestForMock();
        if (StringUtils.isNotBlank(tokenValue)) {
            mockRequest.headerMap.put(tokenName, tokenValue);
        }
        SaManager.getSaTokenContext().setContext(mockRequest, new SaResponseForMock(), new SaStorageForMock());
        try {
            return action.get();
        } finally {
            SaManager.getSaTokenContext().clearContext();
        }
    }

    /**
     * 逐张发起（线程池执行，CompletableFuture 供应方）。
     * 通过 SpringUtils.getBean 走代理调 apply，确保 @Transactional 生效。
     * 调用前已由 {@link #runWithOperatorToken} 安装操作人 Sa-Token Mock 上下文，
     * 流程发起、总监自动审批、监听器等行为与 HTTP 线程单个发起一致。
     * <p>
     * 多线程分片：合同之间无交集，按 50 个一组分片并行发起。
     *
     * @param ctx 批量上下文（预加载的应收/事实/申请单），null 时走单合同路径逐单查
     */
    private CommissionBatchResultVo doBatchApply(String period, LinkedHashSet<String> contractNos,
                                        Long operatorId, BatchApplyContext ctx) {
        CommissionApplicationService self = SpringUtils.getBean(CommissionApplicationService.class);
        CommissionBatchResultVo result = new CommissionBatchResultVo();
        result.setTotal(contractNos.size());
        // 批量预查：一条 IN 查询取所有合同当月活跃+驳回申请单（P1：N×3→1 次）
        if (ctx != null) {
            loadApplicationsBatch(period, contractNos, ctx);
        }
        List<String> contractList = new ArrayList<>(contractNos);
        // 按 50 个一组分片并行
        int chunkSize = 50;
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < contractList.size(); i += chunkSize) {
            chunks.add(contractList.subList(i, Math.min(i + chunkSize, contractList.size())));
        }
        List<CompletableFuture<CommissionBatchResultVo>> futures = chunks.stream()
            .map(chunk -> CompletableFuture.supplyAsync(
                () -> doBatchApplyChunk(period, chunk, operatorId, ctx, self), taskExecutor))
            .toList();
        for (CompletableFuture<CommissionBatchResultVo> f : futures) {
            try {
                CommissionBatchResultVo chunkResult = f.join();
                result.getSuccessContracts().addAll(chunkResult.getSuccessContracts());
                result.getSkippedContracts().addAll(chunkResult.getSkippedContracts());
                result.getFailedContracts().addAll(chunkResult.getFailedContracts());
            } catch (Exception e) {
                log.error("[结佣-批量发起] 分片处理异常", e);
            }
        }
        result.setSuccess(result.getSuccessContracts().size());
        result.setSkipped(result.getSkippedContracts().size());
        result.setFailed(result.getFailedContracts().size());
        log.info("[结佣-批量发起] period={}, 分片={}, 成功={}, 跳过={}, 失败={}",
            period, chunks.size(), result.getSuccess(), result.getSkipped(), result.getFailed());
        return result;
    }

    /** 单分片发起 */
    private CommissionBatchResultVo doBatchApplyChunk(String period, List<String> contractNos,
                                                       Long operatorId, BatchApplyContext ctx,
                                                       CommissionApplicationService self) {
        CommissionBatchResultVo result = new CommissionBatchResultVo();
        for (String contractNo : contractNos) {
            try {
                CommissionApplication existing = ctx != null
                    ? ctx.activeApps.get(contractNo)
                    : findActiveApplication(period, contractNo);
                // DRAFT（实收通过自动生成的草稿）放行，由 apply 直接提交；
                // SUBMITTED/APPROVED/LOCKED 等未完结单跳过；REJECTED 不在活跃单集合内，走重提
                if (existing != null && existing.getStatus() != ApplicationStatus.DRAFT) {
                    result.getSkippedContracts().add(contractNo);
                    continue;
                }
                self.apply(period, contractNo, operatorId, true, ctx);
                result.getSuccessContracts().add(contractNo);
            } catch (Exception e) {
                result.getFailedContracts().add(contractNo);
                log.warn("[结佣-批量发起] 合同 {} 发起失败：{}", contractNo, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 构建结佣申请单 + 明细（不含封账校验与提交，由调用方保证/决定；人工发起与
     * 实收审批通过自动建单共用）。幂等：已有活跃单抛 ServiceException（调用方自行吞掉跳过）。
     * <p>
     * 明细口径（2026-09-27 定稿）：明细源 = 该合同跨月 ACTIVE 新签事实逐行，
     * 金额 = 事实当前值（调整后），绑新签事实 ID；实收事实仅用于 §3.2 审批校验与合同快照。
     *
     * @param ctx 批量上下文，非 null 时复用预加载的活跃单/事实/应收金额，跳过逐单查
     */
    @Transactional(rollbackFor = Exception.class)
    public CommissionApplication createApplicationWithItems(String period, String contractNo, Long operatorId,
                                                            BatchApplyContext ctx) {
        // 幂等检查：从 ctx 取预加载的活跃单，避免逐单查（P1）
        CommissionApplication existing = ctx != null
            ? ctx.activeApps.get(contractNo)
            : findActiveApplication(period, contractNo);
        if (existing != null) {
            if (existing.getStatus() == ApplicationStatus.APPROVED
                || existing.getStatus() == ApplicationStatus.LOCKED) {
                throw new ServiceException("合同 " + contractNo + " " + period
                    + " 月结佣已审批锁定，新增或变更一律走调整单");
            }
            throw new ServiceException("合同 " + contractNo + " " + period
                + " 月已存在" + existing.getStatus().getDesc() + "申请单（" + existing.getApplyNo() + "），请勿重复发起");
        }

        // 拉取该合同 ACTIVE 实收事实：从 ctx 复用同步阶段缓存，避免重复查（P0）；
        // 跨期查找（period 传 null）：实收月可与发起月不同（6 月实收 8 月发起）
        List<PerformanceFactSummaryDTO> facts = ctx != null && ctx.factsMap.containsKey(contractNo)
            ? ctx.factsMap.get(contractNo)
            : performanceQueryPort.findActiveByContract(null, contractNo, FACT_TYPE_REAL);
        List<PerformanceFactSummaryDTO> nonZeroFacts = filterNonZero(facts);
        if (nonZeroFacts.isEmpty()) {
            throw new ServiceException("合同 " + contractNo
                + " 无实收记录，暂不能发起结佣（实收审批通过后方可结佣）");
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

        // 员工归属：明细数据源为跨月新签事实（导入未匹配即拦截，必有人），无需兜底补齐

        // 合同快照（订单号/房源/签约时间取事实聚合值；跨门店合作单 dept_id 留空）
        PerformanceFactSummaryDTO first = nonZeroFacts.get(0);
        String orderNo = null;
        String propertyAddress = null;
        LocalDateTime businessDate = null;
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
        application.setBusinessDate(businessDate);
        application.setDeptId(deptIds.size() == 1 ? first.getDeptId() : null);
        application.setStatus(ApplicationStatus.DRAFT);
        application.setApplicantId(operatorId);
        // 结佣金额口径（2026-09-30 定稿）：当月有新签用当月，当月无新签用历史（实收月之前全部月合计）
        // 金额 = 新签事实当前值（调整后）；实收事实仅用于 §3.2 审批校验与合同快照
        List<PerformanceFactSummaryDTO> itemFacts = loadExpectItemFacts(nonZeroFacts, period);
        if (itemFacts.isEmpty()) {
            throw new ServiceException(
                "合同 " + contractNo + " " + period + " 月的新签业绩全部为 0 或缺失，无结佣明细可生成");
        }
        application.setItemCount(itemFacts.size());
        // 结佣金额合计 = Σ明细金额（新签口径，与 expectedAmount 同基数；
        // 到账是否覆盖新签已由实收域按合同维度判定，此处到账金额不参与计算）
        application.setTotalAmount(sumAmounts(itemFacts));
        // 应收金额：跨月口径（该合同/订单全部月份新签合计），与建单分流判定基数一致；
        // 从 ctx 取预加载值，避免逐单全表查（P0）
        application.setExpectedAmount(ctx != null
            ? ctx.expectedAmounts.getOrDefault(contractNo, BigDecimal.ZERO)
            : resolveExpectedAmount(period, contractNo));
        application.setAligned(false);
        try {
            applicationMapper.insert(application);
        } catch (DuplicateKeyException e) {
            // 并发发起撞 uk_capp_period_contract 部分唯一索引 → 转友好提示
            throw new ServiceException("合同 " + contractNo + " " + period + " 月申请单已由他人发起，请刷新");
        }

        // 批量插入明细：insertBatch 替代逐条 insert（P1：N×K→1 次 round-trip）
        List<CommissionItem> items = new ArrayList<>(itemFacts.size());
        for (PerformanceFactSummaryDTO fact : itemFacts) {
            items.add(buildItem(application, fact, fact.getAmount(), null));
        }
        itemMapper.insertBatch(items);

        log.info("[结佣-发起] 合同申请单已创建：applyNo={}, period={}, contractNo={}, itemCount={}, received={}, expected={}",
            application.getApplyNo(), period, contractNo, application.getItemCount(),
            application.getTotalAmount(), application.getExpectedAmount());
        return application;
    }

    /**
     * 重建结佣明细（新签调整后旧明细全部被冲销时，从当前 ACTIVE 新签事实重新生成）。
     * <p>
     * 场景：用户加角色人/调整金额 → 新签事实 supersede → FACT_REVERSED 把旧结佣明细置 REVERSED；
     * FACT_CREATED 不重建明细（设计如此）。提交前发现 DRAFT 单没有 ACTIVE 明细，
     * 从当前新签事实跨月加载并重建，口径与 {@link #createApplicationWithItems} 一致。
     */
    private void rebuildItems(CommissionApplication application, String period, String contractNo) {
        List<PerformanceFactSummaryDTO> facts =
            performanceQueryPort.findActiveByContract(null, contractNo, FACT_TYPE_REAL);
        List<PerformanceFactSummaryDTO> nonZeroFacts = filterNonZero(facts);
        if (nonZeroFacts.isEmpty()) {
            throw new ServiceException("合同 " + contractNo
                + " 无实收记录，暂不能发起结佣（实收审批通过后方可结佣）");
        }
        List<PerformanceFactSummaryDTO> itemFacts = loadExpectItemFacts(nonZeroFacts, period);
        if (itemFacts.isEmpty()) {
            throw new ServiceException(
                "合同 " + contractNo + " " + period + " 月的新签业绩全部为 0 或缺失，无结佣明细可生成");
        }
        List<CommissionItem> items = new ArrayList<>(itemFacts.size());
        for (PerformanceFactSummaryDTO fact : itemFacts) {
            items.add(buildItem(application, fact, fact.getAmount(), null));
        }
        itemMapper.insertBatch(items);
        recalcAggregates(application.getId(), null);
        log.info("[结佣-重建明细] 合同 {} {} 月明细已重建：{} 条",
            contractNo, period, itemFacts.size());
    }

    /**
     * DRAFT 结佣单明细被冲销后自动重建（新签调整 supersede 联动）。
     * <p>
     * 调用时机：CommissionReverseService.handleReversed 冲销 DRAFT 明细后调用。
     * 此时新签事实已落库（supersede 同事务，outbox 事件提交后投递）。
     * 仅 DRAFT 单自动重建；SUBMITTED/APPROVED 需人工走调整单。
     */
    public void rebuildItemsIfNeeded(Long applicationId) {
        CommissionApplication app = applicationMapper.selectById(applicationId);
        if (app == null || app.getStatus() != ApplicationStatus.DRAFT) {
            return;
        }
        long activeItems = itemMapper.selectCount(new LambdaQueryWrapper<CommissionItem>()
            .eq(CommissionItem::getApplicationId, applicationId)
            .ne(CommissionItem::getStatus, ItemStatus.REVERSED));
        if (activeItems > 0) {
            return;
        }
        try {
            rebuildItems(app, app.getPeriod(), app.getContractNo());
        } catch (ServiceException e) {
            log.info("[结佣-自动重建] 合同 {} {} 月重建跳过：{}", app.getContractNo(), app.getPeriod(), e.getMessage());
        }
    }

    /**
     * 新签调整冲销审批中的结佣单时回退到 DRAFT 并按调整后新签金额重建明细（2026-09-30）。
     * <p>
     * 场景：结佣单处于 SUBMITTED（审批中），新签事实 supersede → FACT_REVERSED
     * 把旧 PENDING 明细冲销为 REVERSED。规则：终止当前审批流程 + 单状态回 DRAFT
     * （保留申请单，用户调整后重新提交）+ 按调整后新签事实重建明细（金额=调整后值）。
     * <p>
     * 时序保证：{@link ApprovalPort#cancel} 走 Warm-Flow 删除链路，同步发 ApprovalEvent(cancel)，
     * Spring {@code @EventListener} 在同事务线程内同步触发 {@link #handleWorkflowEvent}
     * 的 cancel 分支置 CANCELLED + 冲销未审批明细（幂等）。cancel 返回后本方法覆盖回 DRAFT，
     * 无异步时序冲突。幂等：非 SUBMITTED 单走原 {@link #rebuildItemsIfNeeded} 路径。
     */
    @Transactional(rollbackFor = Exception.class)
    public void revertSubmittedToDraftIfNeeded(Long applicationId) {
        revertSubmittedToDraftIfNeeded(applicationId, null);
    }

    /**
     * 同 {@link #revertSubmittedToDraftIfNeeded()}，并携带本次冲销原因（DRAFT 单整单重建时
     * 写入被连带作废明细的 reversed_reason）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void revertSubmittedToDraftIfNeeded(Long applicationId, ReversedReason reason) {
        CommissionApplication app = applicationMapper.selectById(applicationId);
        if (app == null) {
            return;
        }
        if (app.getStatus() != ApplicationStatus.SUBMITTED) {
            // DRAFT 单：冲销事件可能只命中部分明细（增加角色人仅 supersede 被扣除行、
            // 明细级金额调整只 supersede 一行），其余未命中明细仍是调整前旧快照。
            // 先把同单其余未审批明细一并作废（rebuildItemsIfNeeded 仅在无 ACTIVE 明细时重建），
            // 再按当前全部 ACTIVE 新签事实整单重建，保证 DRAFT 单金额与新签完全一致。
            if (app.getStatus() == ApplicationStatus.DRAFT) {
                itemMapper.update(null, new LambdaUpdateWrapper<CommissionItem>()
                    .eq(CommissionItem::getApplicationId, applicationId)
                    .in(CommissionItem::getStatus, ItemStatus.DRAFT, ItemStatus.PENDING)
                    .set(CommissionItem::getStatus, ItemStatus.REVERSED)
                    .set(reason != null, CommissionItem::getReversedReason, reason));
            }
            // 非 SUBMITTED 单：保持 handleReversed 既有行为（DRAFT 单重建、APPROVED/LOCKED 不动）
            recalcAggregates(applicationId, null);
            rebuildItemsIfNeeded(applicationId);
            return;
        }
        // SUBMITTED 单：先终止运行中的审批流程（触发 cancel 事件 → CANCELLED + 冲销 PENDING 明细，幂等）
        if (StringUtils.isNotBlank(app.getProcessInstanceId())) {
            approvalPort.cancel(BizType.COMMISSION, applicationId);
        }
        // cancel 事件已同步置 CANCELLED 并冲销明细；此处覆盖回 DRAFT 保留申请单供用户重新提交
        app = applicationMapper.selectById(applicationId);
        if (app == null) {
            return;
        }
        app.setStatus(ApplicationStatus.DRAFT);
        app.setProcessInstanceId(null);
        app.setCurrentNode(null);
        app.setApproverId(null);
        app.setApproveTime(null);
        applicationMapper.updateById(app);
        log.info("[结佣-审批回退] 新签调整导致 SUBMITTED 单回退 DRAFT：applyNo={}, contractNo={}, period={}",
            app.getApplyNo(), app.getContractNo(), app.getPeriod());
        // 按调整后新签事实重建明细（金额=调整后值）；重建失败不阻断（保留 DRAFT 空单由人工处理）
        try {
            rebuildItems(app, app.getPeriod(), app.getContractNo());
        } catch (ServiceException e) {
            log.info("[结佣-审批回退] 合同 {} {} 月重建跳过：{}", app.getContractNo(), app.getPeriod(), e.getMessage());
        }
        recalcAggregates(applicationId, null);
    }

    /**
     * 结佣明细源（2026-09-27 定稿新签口径）：按合同/订单双键跨月取 ACTIVE 新签事实
     * （PERF_EXPECT，每条事实一行，金额=事实当前值即调整后值）。
     * <ul>
     *   <li>金额为 0 的新签行不生成明细（结佣明细不存在 0 金额行）；</li>
     *   <li>空经纪人实收行天然不在此列（实收明细仅展示，不参与任何计算）。</li>
     * </ul>
     */
    private List<PerformanceFactSummaryDTO> loadExpectItemFacts(List<PerformanceFactSummaryDTO> realFacts,
                                                                 String period) {
        // 匹配键（2026-09-30）：合同号优先，合同号为空用订单号。
        // 贝壳新签源数据 orderNo 可能误填成合同号（与实收 orderNo 不同），有 contractNo 时按合同号
        // 关联新签；findActiveByBizKeys 内部按 order_no OR contract_no 双键匹配
        java.util.Set<String> bizKeys = new java.util.LinkedHashSet<>();
        for (PerformanceFactSummaryDTO f : realFacts) {
            String key = (f.getContractNo() != null && !f.getContractNo().isBlank())
                ? f.getContractNo() : f.getOrderNo();
            if (key != null && !key.isBlank()) {
                bizKeys.add(key);
            }
        }
        List<PerformanceFactSummaryDTO> expects =
            performanceQueryPort.findActiveByBizKeys(bizKeys, FACT_TYPE_EXPECT);
        // 结佣金额口径（2026-09-30 定稿）：当月有非零新签用当月，当月为 0/无新签用历史
        // （实收月之前全部月合计）。0 元新签不参与计算、不视为"当月有新签"，仍回退汇总历史
        List<PerformanceFactSummaryDTO> currentMonth = new ArrayList<>();
        List<PerformanceFactSummaryDTO> history = new ArrayList<>();
        for (PerformanceFactSummaryDTO e : expects) {
            // 新签事实必有人（导入未匹配即拦截）；空归属行为防御性过滤
            if (e.getEmployeeId() == null) {
                continue;
            }
            if (e.getAmount() == null || e.getAmount().signum() == 0) {
                continue;
            }
            String ep = e.getPeriod();
            if (period.equals(ep)) {
                currentMonth.add(e);
            } else if (ep != null && ep.compareTo(period) < 0) {
                history.add(e);
            }
        }
        return !currentMonth.isEmpty() ? currentMonth : history;
    }

    /**
     * 应收合计：跨月口径（该合同/订单全部月份 ACTIVE PERF_EXPECT 合计，与建单分流判定基数一致）。
     * 查询按「订单号 OR 合同号」双列匹配，用户输入订单号或合同号均可命中。
     */
    private BigDecimal resolveExpectedAmount(String period, String contractNo) {
        return performanceQueryPort.sumExpectAmountsByKeysCrossPeriod(java.util.List.of(contractNo))
            .getOrDefault(contractNo, BigDecimal.ZERO);
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
     * <p>结佣金额=新签口径，realAmount 与 expectedAmount 同基数天然一致；
     * 是否跳过财务节点由 {@code panjia.commission.skip_finance_when_match} 开关决定：
     * 开关开则令网关 eq 命中跳过财务，关则故意写不等值走财务人工审批。</p>
     */
    private void updateAmountVariables(CommissionApplication application) {
        BigDecimal expectedAmount = application.getExpectedAmount() == null
            ? BigDecimal.ZERO : application.getExpectedAmount();
        boolean skipEnabled = Boolean.TRUE.equals(
            configService.getConfigBool(CONFIG_SKIP_FINANCE_WHEN_MATCH));
        BigDecimal realAmount = skipEnabled
            ? expectedAmount : expectedAmount.add(BigDecimal.ONE);
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
    public CompletableFuture<CommissionBatchResultVo> batchApproveByContract(
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
        // 同步阶段过滤：在 HTTP 线程中有 Sa-Token 上下文。
        // ① 一条 IN 查询取所有 SUBMITTED 申请单（替代逐单 selectOne 的 N 次查询）；
        // ② myCurrentTasks 以 3 条 SQL 完成全部单据的待办鉴权（替代逐单 isMyTask 的 3N 条 SQL）。
        CommissionBatchResultVo syncResult = new CommissionBatchResultVo();
        syncResult.setTotal(deduped.size());
        Map<String, CommissionApplication> submittedApps = loadSubmittedApplicationsBatch(period, deduped);
        List<CommissionApplication> appList = submittedApps.values().stream().distinct().toList();
        Map<Long, MyTaskBrief> myTaskMap = appList.isEmpty()
            ? Map.of()
            : approvalPort.myCurrentTasks(BizType.COMMISSION,
                appList.stream().map(CommissionApplication::getId).toList());
        // 按用户输入顺序组装可办任务，异步直接用 taskId 办理
        List<BatchApproveItem> approveItems = new ArrayList<>(deduped.size());
        for (String contractNo : deduped) {
            CommissionApplication application = submittedApps.get(contractNo);
            if (application == null) {
                syncResult.getSkippedContracts().add(contractNo);
                continue;
            }
            MyTaskBrief task = myTaskMap.get(application.getId());
            if (task == null) {
                syncResult.getSkippedContracts().add(contractNo);
                continue;
            }
            approveItems.add(new BatchApproveItem(contractNo, application, task));
        }
        syncResult.setSkipped(syncResult.getSkippedContracts().size());
        syncResult.setFailed(syncResult.getFailedContracts().size());
        log.info("[结佣-批量审批] period={}, total={}, myTasks={}, skipped={}, operator={}",
            period, deduped.size(), approveItems.size(), syncResult.getSkipped(), operatorId);
        final CommissionBatchResultVo preResult = syncResult;
        return CompletableFuture.supplyAsync(
            () -> {
                CommissionBatchResultVo asyncResult = doBatchApprove(period, approveItems);
                preResult.getSkippedContracts().forEach(asyncResult.getSkippedContracts()::add);
                preResult.getFailedContracts().forEach(asyncResult.getFailedContracts()::add);
                asyncResult.setTotal(preResult.getTotal());
                asyncResult.setSkipped(asyncResult.getSkippedContracts().size());
                asyncResult.setFailed(asyncResult.getFailedContracts().size());
                return asyncResult;
            }, taskExecutor);
    }

    /**
     * 一条 IN 查询批量取指定合同当月 SUBMITTED 申请单（批量审批预检用）。
     * <p>按输入字符串（合同号或订单号）双键建映射，同一合同取 ID 最大（最新）一张。
     */
    private Map<String, CommissionApplication> loadSubmittedApplicationsBatch(
            String period, Collection<String> contractNos) {
        if (contractNos.isEmpty()) {
            return Map.of();
        }
        List<CommissionApplication> apps = applicationMapper.selectList(
            new LambdaQueryWrapper<CommissionApplication>()
                .eq(CommissionApplication::getPeriod, period)
                .and(w -> w.in(CommissionApplication::getContractNo, contractNos)
                    .or().in(CommissionApplication::getOrderNo, contractNos))
                .eq(CommissionApplication::getStatus, ApplicationStatus.SUBMITTED)
                .orderByDesc(CommissionApplication::getId));
        Map<String, CommissionApplication> byContract = new HashMap<>();
        Map<String, CommissionApplication> byOrder = new HashMap<>();
        for (CommissionApplication app : apps) {
            if (app.getContractNo() != null) {
                byContract.putIfAbsent(app.getContractNo(), app);
            }
            if (app.getOrderNo() != null) {
                byOrder.putIfAbsent(app.getOrderNo(), app);
            }
        }
        Map<String, CommissionApplication> result = new HashMap<>(contractNos.size() * 2);
        for (String input : contractNos) {
            CommissionApplication app = byContract.getOrDefault(input, byOrder.get(input));
            if (app != null) {
                result.put(input, app);
            }
        }
        return result;
    }

    /**
     * 逐单审批（线程池执行，CompletableFuture 供应方）。
     * 申请单与当前待办（taskId/nodeCode）均由同步阶段预检透传，异步不再查申请单/当前任务；
     * 用 completeTaskAsSys 按 taskId 办理（ignore=true），权限已在预检阶段闭合。
     * 预检后任务若被他人抢先办理，引擎抛异常计入失败（并发安全）。
     */
    /**
     * 逐单审批（线程池执行）。
     * <p>
     * 多线程分片：合同之间无交集，按 50 个一组分片并行办理。
     */
    private CommissionBatchResultVo doBatchApprove(String period, List<BatchApproveItem> items) {
        CommissionBatchResultVo result = new CommissionBatchResultVo();
        result.setTotal(items.size());
        if (items.isEmpty()) {
            return result;
        }
        // 按 50 个一组分片并行
        int chunkSize = 50;
        List<List<BatchApproveItem>> chunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i += chunkSize) {
            chunks.add(items.subList(i, Math.min(i + chunkSize, items.size())));
        }
        List<CompletableFuture<CommissionBatchResultVo>> futures = chunks.stream()
            .map(chunk -> CompletableFuture.supplyAsync(
                () -> doBatchApproveChunk(chunk), taskExecutor))
            .toList();
        for (CompletableFuture<CommissionBatchResultVo> f : futures) {
            try {
                CommissionBatchResultVo chunkResult = f.join();
                result.getSuccessContracts().addAll(chunkResult.getSuccessContracts());
                result.getSkippedContracts().addAll(chunkResult.getSkippedContracts());
                result.getFailedContracts().addAll(chunkResult.getFailedContracts());
            } catch (Exception e) {
                log.error("[结佣-批量审批] 分片处理异常", e);
            }
        }
        result.setSuccess(result.getSuccessContracts().size());
        result.setSkipped(result.getSkippedContracts().size());
        result.setFailed(result.getFailedContracts().size());
        log.info("[结佣-批量审批] period={}, 分片={}, 成功={}, 跳过={}, 失败={}",
            period, chunks.size(), result.getSuccess(), result.getSkipped(), result.getFailed());
        return result;
    }

    /** 单分片审批 */
    private CommissionBatchResultVo doBatchApproveChunk(List<BatchApproveItem> items) {
        CommissionBatchResultVo result = new CommissionBatchResultVo();
        for (BatchApproveItem item : items) {
            String contractNo = item.contractNo;
            try {
                String node = item.task.getNodeCode();
                if (!NODE_DIRECTOR.equals(node) && !NODE_FINANCE.equals(node)) {
                    result.getSkippedContracts().add(contractNo);
                    continue;
                }
                if (NODE_DIRECTOR.equals(node)) {
                    updateAmountVariables(item.application);
                }
                String defaultComment = NODE_DIRECTOR.equals(node) ? "总监审批通过" : "财务审批通过";
                approvalPort.completeTaskAsSys(item.task.getTaskId(), "批量审批：" + defaultComment);
                refreshCurrentNode(item.application);
                result.getSuccessContracts().add(contractNo);
            } catch (Exception e) {
                result.getFailedContracts().add(contractNo);
                log.warn("[结佣-批量审批] 合同 {} 审批失败：{}", contractNo, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 作废申请单：DRAFT/SUBMITTED/REJECTED/LOCKED 可作废；
     * 超管可作废任意单据，其他用户仅可作废本人发起的单据（服务端兜底防越权）。
     * <p>
     * - 未锁定单（DRAFT/SUBMITTED/REJECTED）：冲销未审批明细（DRAFT/PENDING），释放事实；
     * - 已锁定单（LOCKED）：冲销全部明细（含 APPROVED），释放事实，薪资域 findLocked 仅取 APPROVED，
     *   作废后该单不再计入工资；后续可重新发起，按发起日重新生成当月结佣记录。
     * 运行中的流程先终止（cancel 事件回调冲销明细）；REJECTED/LOCKED 单据的流程已结束，
     * deleteInstanceSys 走标准删除链清理实例记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long applicationId, Long operatorId) {
        CommissionApplication application = getApplication(applicationId);
        if (!LoginHelper.isSuperAdmin()
            && !Objects.equals(application.getApplicantId(), operatorId)) {
            throw new ServiceException("仅可作废本人发起的申请单");
        }
        if (application.getStatus() != ApplicationStatus.DRAFT
            && application.getStatus() != ApplicationStatus.SUBMITTED
            && application.getStatus() != ApplicationStatus.REJECTED
            && application.getStatus() != ApplicationStatus.LOCKED) {
            throw new ServiceException("仅草稿/审批中/已驳回/已锁定状态可作废（当前：" + application.getStatus().getDesc() + "）");
        }
        // 封账后不可作废（含已锁定单）：封账期间结佣数据已固化，作废会导致已发工资追溯，必须先解封
        checkPeriodOpen(application.getPeriod(), "作废结佣");
        if (StringUtils.isNotBlank(application.getProcessInstanceId())) {
            // 终止运行中的流程实例（触发 cancel 事件，监听器置 CANCELLED + 冲销明细，幂等）
            approvalPort.cancel(BizType.COMMISSION, applicationId);
        }
        // 草稿无流程实例：本地直接置 CANCELLED 并冲销明细；
        // 已锁定单：冲销全部明细（含 APPROVED），释放事实供重新发起
        reverseAllItems(applicationId);
        // 明细 UPDATE 会清空 MyBatis 一级缓存并推进数据版本，这里重新加载避免乐观锁更新丢失
        application = applicationMapper.selectById(applicationId);
        if (application != null && application.getStatus() != ApplicationStatus.CANCELLED) {
            application.setItemCount(0);
            application.setTotalAmount(BigDecimal.ZERO);
            application.setStatus(ApplicationStatus.CANCELLED);
            application.setCurrentNode(null);
            applicationMapper.updateById(application);
        }
        log.info("[结佣-作废] applyNo={}, fromStatus={}, operator={}",
            application == null ? applicationId : application.getApplyNo(),
            application == null ? "?" : application.getStatus(), operatorId);
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
                LocalDateTime approvedAt = LocalDateTime.now();
                if (handlerId != null) {
                    application.setApproverId(handlerId);
                }
                application.setApproveTime(approvedAt);
                application.setLockTime(approvedAt);
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
                // 与实收审批单口径一致：驳回也留痕审批人/审批时间
                if (handlerId != null) {
                    application.setApproverId(handlerId);
                    application.setApproveTime(LocalDateTime.now());
                }
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

    public PageResult<CommissionApplication> listApplications(CommissionApplyBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<CommissionApplication> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.isNotBlank(query.getPeriod()), CommissionApplication::getPeriod, query.getPeriod())
            .eq(StringUtils.isNotBlank(query.getStatus()), CommissionApplication::getStatus,
                ApplicationStatus.fromCode(query.getStatus()))
            .and(StringUtils.isNotBlank(query.getKeyword()), w -> w
                .like(CommissionApplication::getContractNo, query.getKeyword())
                .or().like(CommissionApplication::getOrderNo, query.getKeyword())
                .or().like(CommissionApplication::getPropertyAddress, query.getKeyword()));
        // 部门数据权限：业务角色（总监/店长/经纪人）限定本部门（含下级）；财务/超管不限制
        Long effectiveDeptId = DeptScopeUtils.enforceSelfDeptScope(query.getDeptId(), deptService::selectDeptAndChildById, "结佣");
        if (effectiveDeptId != null) {
            wrapper.in(CommissionApplication::getDeptId, deptService.selectDeptAndChildById(effectiveDeptId));
        }
        wrapper.orderByDesc(CommissionApplication::getCreateTime);
        var page = applicationMapper.selectPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    /** 有结佣申请单的期间（YYYY-MM，倒序），供前端默认选中最新有数据期间 */
    public java.util.List<String> listPeriods() {
        return applicationMapper.selectDistinctPeriods();
    }

    /**
     * 按「合同」维度分页查询结佣申请（与业绩明细页合同维度对齐）。
     */
    public PageResult<CommissionContractVo> listContracts(CommissionApplyBo query, PageQuery pageQuery) {
        // period 可空：仅录合同号等关键字不选期间时跨期查询（与业绩明细列表口径一致）
        String period = StringUtils.trimToNull(query.getPeriod());

        // 部门数据权限：业务角色（总监/店长/经纪人）限定本部门（含下级）；财务/超管不限制
        Long effectiveDeptId = DeptScopeUtils.enforceSelfDeptScope(query.getDeptId(), deptService::selectDeptAndChildById, "结佣");

        List<PerformanceContractSummaryDTO> contracts =
            performanceQueryPort.listContractSummaries(period, effectiveDeptId, FACT_TYPE_REAL, query.getEmployeeId());

        List<CommissionApplication> applications = applicationMapper.selectList(new LambdaQueryWrapper<CommissionApplication>()
            .eq(StringUtils.isNotBlank(period), CommissionApplication::getPeriod, period)
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
        String bizType = StringUtils.trimToNull(query.getBizType());
        // 审批节点数据隔离：审批中单据仅本人角色对应节点可见（财务→FINANCE，总监→DIRECTOR），超管看全部
        boolean nodeScopeAll = LoginHelper.isSuperAdmin();
        Set<String> myNodes = nodeScopeAll ? Set.of() : currentApprovalNodes();

        // 折算因子：本轮合同共用一次批量查询（同一 bizType 同一因子），逐行经公共方法取值
        Map<String, BigDecimal> factorMap = conversionFactorPort.factorsOf(
            contracts.stream().map(PerformanceContractSummaryDTO::getBizType).collect(Collectors.toSet()));

        List<CommissionContractVo> all = new ArrayList<>(contracts.size());
        // 封账判定：固定期间一次判定；跨期查询按行所属期间逐个判定（懒加载缓存，避免逐行回表）
        Map<String, Boolean> closedCache = new HashMap<>();
        Boolean fixedClosed = period != null ? periodCloseQueryPort.isClosed(period) : null;
        for (PerformanceContractSummaryDTO c : contracts) {
            CommissionApplication app = appMap.get(c.getContractNo());
            if (app == null && StringUtils.isNotBlank(c.getOrderNo())) {
                app = appMap.get(c.getOrderNo());
            }
            // 实收审批通过后系统自动生成结佣草稿单，无申请单的合同不在结佣明细展示
            if (app == null || app.getStatus() == null) {
                continue;
            }
            String status = app.getStatus().getCode();
            if (StringUtils.isNotBlank(query.getStatus()) && !query.getStatus().equals(status)) {
                continue;
            }
            // 审批节点数据隔离：审批中（SUBMITTED）单据仅「本人角色对应节点」或「申请人本人」可见；
            // 财务→FINANCE、总监→DIRECTOR，店长/经纪人看不到他人审批中的单据但能看到自己发起的，超管看全部
            if (!nodeScopeAll && ApplicationStatus.SUBMITTED.getCode().equals(status)
                && (!myNodes.contains(app.getCurrentNode())
                    && !Objects.equals(app.getApplicantId(), LoginHelper.getUserId()))) {
                continue;
            }
            if (keyword != null && !containsKeyword(c, keyword)) {
                continue;
            }
            if (bizType != null && !bizType.equals(c.getBizType())) {
                continue;
            }
            // 行级期间/封账：固定期间直接用；跨期时行期间取申请单归属期间
            String rowPeriod = period != null ? period : app.getPeriod();
            boolean rowClosed = fixedClosed != null ? fixedClosed
                : closedCache.computeIfAbsent(rowPeriod, p -> periodCloseQueryPort.isClosed(p));
            all.add(toContractVO(rowPeriod, rowClosed, c, app, status, conversionFactorPort.factorOf(factorMap, c.getBizType())));
        }

        // 三个业绩列表统一排序：签约/认购时间倒序 → 合同号(空取订单号)次序 → id 倒序兜底
        all.sort((a, b) -> {
            if (a.getBusinessDate() == null && b.getBusinessDate() == null) {
                return compareContractRow(a, b);
            }
            if (a.getBusinessDate() == null) {
                return 1;
            }
            if (b.getBusinessDate() == null) {
                return -1;
            }
            int byDate = b.getBusinessDate().compareTo(a.getBusinessDate());
            return byDate != 0 ? byDate : compareContractRow(a, b);
        });
        int total = all.size();
        int pageNum = pageQuery.getPageNum() != null ? pageQuery.getPageNum() : 1;
        int pageSize = pageQuery.getPageSize() != null ? pageQuery.getPageSize() : 20;
        int from = Math.min((pageNum - 1) * pageSize, total);
        int to = Math.min(from + pageSize, total);
        List<CommissionContractVo> pageRows = new ArrayList<>(all.subList(from, to));
        // 结佣业绩「原值 → 调整后值」：仅对当前页批量查事实链最早值（避免全期间回表），
        // 与当前合计不一致时置调整标记（口径与每人明细 originalAmount 一致）
        fillOriginalReceivedAmounts(period, pageRows, factorMap);
        // 合同级在途调整标记：审批中调整单 → 「调整审批中」标签
        fillContractPendingAdjust(pageRows);
        // 合同级「新增角色人」标记：存在已生效的 MANUAL-ADJ/MANUAL-CADJ 新人事实 → 「新增角色人」标签
        fillAddMemberMark(period, pageRows);

        // 跨页全局汇总（统计栏不随分页变化）：合同/明细条数/金额按过滤后全集内存聚合；
        // 涉及人数须跨合同去重（同一员工可能在多个合同），走实收事实 COUNT(DISTINCT 员工键)
        Set<String> summaryKeys = new HashSet<>();
        long summaryDetailCount = 0L;
        BigDecimal summaryTotalAmount = BigDecimal.ZERO;
        for (CommissionContractVo vo : all) {
            if (StringUtils.isNotBlank(vo.getContractNo())) {
                summaryKeys.add(vo.getContractNo());
            }
            if (StringUtils.isNotBlank(vo.getOrderNo())) {
                summaryKeys.add(vo.getOrderNo());
            }
            summaryDetailCount += vo.getDetailCount();
            if (vo.getAmount() != null) {
                summaryTotalAmount = summaryTotalAmount.add(vo.getAmount());
            }
        }
        PageResult<CommissionContractVo> result = PageResult.build(pageRows, (long) total);
        Map<String, Object> summary = new HashMap<>();
        summary.put("contractCount", total);
        summary.put("employeeCount", performanceQueryPort.countDistinctEmployeesByKeys(period, summaryKeys, FACT_TYPE_REAL));
        summary.put("detailCount", summaryDetailCount);
        summary.put("totalAmount", summaryTotalAmount);
        result.setSummary(summary);
        return result;
    }

    /**
     * 批量标记当前页合同是否存在已生效的「增加角色人」（口径与新签合同列表一致）。
     * 合同号/订单号双键匹配；不逐行查询。
     */
    private void fillAddMemberMark(String period, List<CommissionContractVo> pageRows) {
        if (pageRows == null || pageRows.isEmpty()) {
            return;
        }
        Set<String> keys = new HashSet<>();
        for (CommissionContractVo r : pageRows) {
            if (StringUtils.isNotBlank(r.getContractNo())) {
                keys.add(r.getContractNo());
            }
            if (StringUtils.isNotBlank(r.getOrderNo())) {
                keys.add(r.getOrderNo());
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        Set<String> hit = new HashSet<>(itemMapper.selectAddMemberBizKeys(period, keys));
        for (CommissionContractVo r : pageRows) {
            r.setHasAddMember(hit.contains(r.getContractNo()) || hit.contains(r.getOrderNo()));
        }
    }

    /**
     * 批量填充列表行的结佣业绩调整前合计。
     * <p>
     * 口径：结佣金额列的「原值 → 调整后值」只反映<b>结佣调整单(AMOUNT)</b>，不反映新签调整——
     * 新签调整（含新签侧增加角色人）只在「新签业绩」列体现。原额 = 当前合计 − 该申请单下
     * 已执行 AMOUNT 调整单的累计差额；无差额（纯新签调整/未调整）时保持 null，前端只显示单值。
     */
    private void fillOriginalReceivedAmounts(String period, List<CommissionContractVo> pageRows,
                                             Map<String, BigDecimal> factorMap) {
        if (pageRows == null || pageRows.isEmpty()) {
            return;
        }
        Set<Long> applicationIds = pageRows.stream()
            .map(CommissionContractVo::getApplicationId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        if (applicationIds.isEmpty()) {
            return;
        }
        Map<Long, BigDecimal> deltaByApp = new HashMap<>();
        for (Map<String, Object> row : adjustMapper.selectExecutedAmountDeltaSum(applicationIds)) {
            Object id = row.get("applicationId");
            Object delta = row.get("deltaSum");
            if (id != null && delta instanceof BigDecimal bd) {
                deltaByApp.put(((Number) id).longValue(), bd);
            }
        }
        if (deltaByApp.isEmpty()) {
            return;
        }
        for (CommissionContractVo vo : pageRows) {
            if (vo.getApplicationId() == null || vo.getAmount() == null) {
                continue;
            }
            BigDecimal delta = deltaByApp.get(vo.getApplicationId());
            if (delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            BigDecimal original = vo.getAmount().subtract(delta);
            vo.setReceivedAdjusted(true);
            vo.setOriginalAmount(original);
            BigDecimal factor = conversionFactorPort.factorOf(factorMap, vo.getBizType());
            vo.setOriginalReceivedConvertedAmount(conversionFactorPort.convert(original, factor));
        }
    }

    /**
     * 合同行兜底排序（签约时间相同时）：订单号（空取合同号）升序，仍相同按申请单 ID 倒序。
     * 与新签/实收列表 SQL 的 COALESCE(order_no, contract_no), id DESC 口径一致。
     */
    private static int compareContractRow(CommissionContractVo a, CommissionContractVo b) {
        String ka = StringUtils.isNotBlank(a.getOrderNo()) ? a.getOrderNo() : a.getContractNo();
        String kb = StringUtils.isNotBlank(b.getOrderNo()) ? b.getOrderNo() : b.getContractNo();
        if (ka == null && kb == null) {
            return 0;
        }
        if (ka == null) {
            return 1;
        }
        if (kb == null) {
            return -1;
        }
        int byKey = ka.compareTo(kb);
        if (byKey != 0) {
            return byKey;
        }
        Long ia = a.getApplicationId();
        Long ib = b.getApplicationId();
        if (ia == null && ib == null) {
            return 0;
        }
        if (ia == null) {
            return 1;
        }
        if (ib == null) {
            return -1;
        }
        return ib.compareTo(ia);
    }

    /**
     * 合同汇总 + 申请单 → 列表行 VO；有单时金额/条数以申请单聚合为准（0 值事实不入单）。
     *
     * @param factor 该合同 bizType 的折算因子（调用方批量取好后传入，避免逐行回表）
     */
    private CommissionContractVo toContractVO(String period, boolean periodClosed, PerformanceContractSummaryDTO c,
                                              CommissionApplication app, String status, BigDecimal factor) {
        CommissionContractVo vo = new CommissionContractVo();
        vo.setPeriod(period);
        vo.setPeriodClosed(periodClosed);
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
            // 应收展示当前 ACTIVE 值（含已生效调整），与快照不一致时标「已调整」，
            // 并把提交快照留存为「调整前」值，供前端展示「原值 → 调整后值」
            if (app.getExpectedAmount() != null && c.getExpectedAmount() != null
                && app.getExpectedAmount().compareTo(c.getExpectedAmount()) != 0) {
                vo.setExpectedAdjusted(true);
                vo.setOriginalExpectedAmount(app.getExpectedAmount());
            }
        } else {
            // 未发起结佣：结佣业绩金额 = 新签应收合计（expectedAmount），实收仅做门控不参与金额
            vo.setAmount(c.getExpectedAmount());
            vo.setDetailCount(c.getDetailCount());
        }
        // 折算后金额：合同维度聚合行无 factId，按本行 bizType 的因子折算（同一合同同一因子，应收/实收同因子）
        vo.setConvertedAmount(conversionFactorPort.convert(vo.getAmount(), factor));
        vo.setExpectedConvertedAmount(conversionFactorPort.convert(vo.getExpectedAmount(), factor));
        // 调整前应收的折算后金额：与当前值同一因子，仅在原值存在时输出（无调整时保持 null，前端不展示）
        if (vo.getOriginalExpectedAmount() != null) {
            vo.setOriginalExpectedConvertedAmount(
                conversionFactorPort.convert(vo.getOriginalExpectedAmount(), factor));
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
        // 签约/认购时间同口径回填：快照为发起时 LocalDate.atStartOfDay() 丢失时分秒，详情取实时事实值
        fillBusinessDateFromFact(application);
        return application;
    }

    /** 按申请单 ID 查流程实例 ID */
    public Long getInstanceId(Long applicationId) {
        return approvalPort.instanceId(BizType.COMMISSION, applicationId);
    }

    /**
     * 查当前 ACTIVE PERF_EXPECT 合计，与申请单快照比较：
     * 不一致时置 expectedAdjusted=true，并用当前值覆盖 expectedAmount 供前端展示。
     * <p>金额口径当月优先（与 loadExpectItemFacts 一致）：当月有<b>非零</b>新签 → 只取当月合计；
     * 当月为 0/无 → 不参与当月计算，取历史（&lt;实收月）合计。
     */
    private void fillExpectedAdjusted(CommissionApplication app) {
        if (app == null || StringUtils.isBlank(app.getPeriod())
            || (StringUtils.isBlank(app.getContractNo()) && StringUtils.isBlank(app.getOrderNo()))) {
            return;
        }
        String lookupKey = StringUtils.isNotBlank(app.getContractNo()) ? app.getContractNo() : app.getOrderNo();
        List<PerformanceFactSummaryDTO> allExpects =
            performanceQueryPort.findActiveByBizKeys(java.util.List.of(lookupKey), FACT_TYPE_EXPECT);
        List<PerformanceFactSummaryDTO> currentMonth = new ArrayList<>();
        List<PerformanceFactSummaryDTO> history = new ArrayList<>();
        for (PerformanceFactSummaryDTO e : allExpects) {
            if (e.getAmount() == null || e.getAmount().signum() == 0) {
                continue;
            }
            String ep = e.getPeriod();
            if (app.getPeriod().equals(ep)) {
                currentMonth.add(e);
            } else if (ep != null && ep.compareTo(app.getPeriod()) < 0) {
                history.add(e);
            }
        }
        List<PerformanceFactSummaryDTO> target = !currentMonth.isEmpty() ? currentMonth : history;
        BigDecimal currentExpected = target.stream()
            .map(PerformanceFactSummaryDTO::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (app.getExpectedAmount() != null && app.getExpectedAmount().compareTo(currentExpected) != 0) {
            app.setExpectedAdjusted(true);
        }
        // 保留提交快照作为「调整前」值，再覆盖为当前值（供详情「应收合计」展示「原值 → 调整后值」）
        app.setOriginalExpectedAmount(app.getExpectedAmount());
        app.setExpectedAmount(currentExpected);
    }

    /**
     * 签约/认购时间实时回填（仅覆盖内存展示值，不落库）。
     * <p>
     * 申请单 business_date 快照在发起时由 {@code LocalDate.atStartOfDay()} 写入，时分秒恒为 00:00:00；
     * 列表行始终取实时事实聚合 {@code MAX(COALESCE(raw_json.signDate, business_date))}（含真实时分秒），
     * 详情按与列表完全同源的合同汇总回填，保证两者口径一致；合同已无 ACTIVE 事实时保留原快照。
     */
    private void fillBusinessDateFromFact(CommissionApplication app) {
        if (app == null || StringUtils.isBlank(app.getPeriod())
            || (StringUtils.isBlank(app.getContractNo()) && StringUtils.isBlank(app.getOrderNo()))) {
            return;
        }
        performanceQueryPort.listContractSummaries(app.getPeriod(), null, FACT_TYPE_REAL, null).stream()
            .filter(c -> (StringUtils.isNotBlank(app.getContractNo())
                && app.getContractNo().equals(c.getContractNo()))
                || (StringUtils.isNotBlank(app.getOrderNo())
                && app.getOrderNo().equals(c.getOrderNo())))
            .map(PerformanceContractSummaryDTO::getBusinessDate)
            .filter(Objects::nonNull)
            .findFirst()
            .ifPresent(app::setBusinessDate);
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
    public List<com.panjia.commission.domain.vo.CommissionItemDetailVo> listItemDetails(Long applicationId) {
        List<com.panjia.commission.domain.vo.CommissionItemDetailVo> details = itemMapper.selectItemDetails(applicationId);
        fillItemDetailConversion(details);
        fillItemDetailPendingAdjust(details, applicationId);
        return details;
    }

    /**
     * 明细在途调整预演：审批中（SUBMITTED/APPROVED）调整单回填每人 adjustPending* 字段。
     * <p>
     * 口径（与新签明细页一致）：
     * <ul>
     *   <li>明细级调整（itemId 定位）：调整后金额取调整单 newAmount；</li>
     *   <li>合同级 AMOUNT：优先按 payload.detailTargets 逐行指定值预演；旧单无快照按金额占比分摊总差额；</li>
     *   <li>合同级 ADD_MEMBER：既有行按 payload.detailTargets 预演让出，新人合成虚拟行（0 → X）；</li>
     *   <li>明细级优先：已命中明细级调整单的行不再叠加合同级预演。</li>
     * </ul>
     */
    private void fillItemDetailPendingAdjust(List<CommissionItemDetailVo> rows, Long applicationId) {
        // 按合同号查询在途调整单（同合同任意在途调整单，含其他期间申请单，均会改共享 PERF_EXPECT 事实）
        CommissionApplication app = applicationMapper.selectById(applicationId);
        String contractNo = app == null ? null : app.getContractNo();
        LambdaQueryWrapper<CommissionAdjust> pendingWrapper = new LambdaQueryWrapper<CommissionAdjust>()
            .in(CommissionAdjust::getStatus, AdjustStatus.SUBMITTED, AdjustStatus.APPROVED)
            .orderByDesc(CommissionAdjust::getId);
        if (StringUtils.isNotBlank(contractNo)) {
            pendingWrapper.eq(CommissionAdjust::getContractNo, contractNo);
        } else {
            pendingWrapper.eq(CommissionAdjust::getApplicationId, applicationId);
        }
        List<CommissionAdjust> pendings = adjustMapper.selectList(pendingWrapper);
        if (pendings.isEmpty()) {
            return;
        }
        for (CommissionAdjust adjust : pendings) {
            if (adjust.getItemId() != null) {
                // 明细级：直接命中目标行
                for (CommissionItemDetailVo row : rows) {
                    if (Boolean.TRUE.equals(row.getAdjustPending())
                        || !adjust.getItemId().equals(row.getItemId())) {
                        continue;
                    }
                    BigDecimal amount = row.getAmount() == null ? BigDecimal.ZERO : row.getAmount();
                    row.setAdjustPending(true);
                    row.setAdjustPendingType(adjust.getAdjustType() != null ? adjust.getAdjustType().getCode() : null);
                    row.setAdjustPendingAmount(adjust.getNewAmount());
                    row.setAdjustPendingDelta(adjust.getNewAmount() == null ? null
                        : adjust.getNewAmount().subtract(amount).setScale(2, RoundingMode.HALF_UP));
                }
                continue;
            }
            // 合同级：AMOUNT / ADD_MEMBER 按快照逐行预演
            CommissionAdjustPayload payload = parseAdjustPayload(adjust.getPayloadJson());
            if (adjust.getAdjustType() == AdjustType.AMOUNT || adjust.getAdjustType() == AdjustType.ADD_MEMBER) {
                Map<Long, BigDecimal> targetByItem = new HashMap<>();
                if (payload != null && payload.getDetailTargets() != null) {
                    for (CommissionAdjustPayload.DetailTarget t : payload.getDetailTargets()) {
                        if (t != null && t.getItemId() != null && t.getTargetAmount() != null) {
                            targetByItem.put(t.getItemId(), t.getTargetAmount());
                        }
                    }
                }
                if (!targetByItem.isEmpty()) {
                    for (CommissionItemDetailVo row : rows) {
                        if (Boolean.TRUE.equals(row.getAdjustPending())) {
                            continue; // 明细级优先，不叠加
                        }
                        BigDecimal target = targetByItem.get(row.getItemId());
                        if (target == null) {
                            continue;
                        }
                        BigDecimal amount = row.getAmount() == null ? BigDecimal.ZERO : row.getAmount();
                        row.setAdjustPending(true);
                        row.setAdjustPendingType(adjust.getAdjustType().getCode());
                        row.setAdjustPendingAmount(target);
                        row.setAdjustPendingDelta(target.subtract(amount).setScale(2, RoundingMode.HALF_UP));
                    }
                } else if (adjust.getAdjustType() == AdjustType.AMOUNT
                    && adjust.getNewAmount() != null && adjust.getOriginalAmount() != null && !rows.isEmpty()) {
                    // 旧单无快照兜底：按金额占比分摊总差额
                    BigDecimal delta = adjust.getNewAmount().subtract(adjust.getOriginalAmount());
                    List<CommissionItemDetailVo> unmarked = rows.stream()
                        .filter(r -> !Boolean.TRUE.equals(r.getAdjustPending())).toList();
                    BigDecimal sum = unmarked.stream()
                        .map(r -> r.getAmount() == null ? BigDecimal.ZERO : r.getAmount())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                    for (CommissionItemDetailVo row : unmarked) {
                        BigDecimal amount = row.getAmount() == null ? BigDecimal.ZERO : row.getAmount();
                        BigDecimal part = sum.signum() == 0
                            ? delta.divide(BigDecimal.valueOf(unmarked.size()), 2, RoundingMode.HALF_UP)
                            : delta.multiply(amount).divide(sum, 2, RoundingMode.HALF_UP);
                        row.setAdjustPending(true);
                        row.setAdjustPendingType(AdjustType.AMOUNT.getCode());
                        row.setAdjustPendingDelta(part);
                        row.setAdjustPendingAmount(amount.add(part));
                    }
                }
                // ADD_MEMBER：新人虚拟行（审批中新角色人尚无明细行）
                if (adjust.getAdjustType() == AdjustType.ADD_MEMBER
                    && payload != null && payload.getNewMember() != null) {
                    CommissionAdjustPayload.NewMember nm = payload.getNewMember();
                    CommissionItemDetailVo virtualRow = new CommissionItemDetailVo();
                    virtualRow.setEmployeeId(nm.getEmployeeId());
                    virtualRow.setEmployeeCode(nm.getEmployeeCode());
                    virtualRow.setEmployeeName(nm.getEmployeeName());
                    virtualRow.setRoleType(nm.getRoleType());
                    virtualRow.setRoleName(nm.getRoleType());
                    virtualRow.setShareRatio(nm.getShareRatio());
                    virtualRow.setBizType(rows.isEmpty() ? null : rows.get(0).getBizType());
                    virtualRow.setStatus(ItemStatus.APPROVED.getCode());
                    virtualRow.setAdjustPending(true);
                    virtualRow.setAdjustPendingType(AdjustType.ADD_MEMBER.getCode());
                    virtualRow.setAdjustPendingAmount(nm.getAmount());
                    virtualRow.setAdjustPendingDelta(nm.getAmount());
                    virtualRow.setNewMemberPending(true);
                    rows.add(virtualRow);
                }
            }
        }
    }

    /** 解析调整单快照，解析失败返回 null（不影响主流程）。 */
    private CommissionAdjustPayload parseAdjustPayload(String payloadJson) {
        if (StringUtils.isBlank(payloadJson)) {
            return null;
        }
        try {
            return JsonUtils.parseObject(payloadJson, CommissionAdjustPayload.class);
        } catch (Exception e) {
            log.warn("[结佣-在途预演] 快照解析失败，跳过逐行还原：payload={}", payloadJson, e);
            return null;
        }
    }

    /**
     * 合同列表在途调整标记：当前页合同存在审批中（SUBMITTED/APPROVED）的结佣调整单时，
     * 回填 adjustPending / adjustPendingType / adjustPendingAmount（AMOUNT=调整后合计），
     * 前端据此展示「调整审批中」标签。
     * <p>2026-10-02 起按合同号互斥（同合同任意在途调整单，含明细级/其他期间申请单），
     * 故查询条件从 applicationId + itemId IS NULL 放宽为 contractNo。
     */
    private void fillContractPendingAdjust(List<CommissionContractVo> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<String> contractNos = rows.stream()
            .map(CommissionContractVo::getContractNo)
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toSet());
        if (contractNos.isEmpty()) {
            return;
        }
        List<CommissionAdjust> pendings = adjustMapper.selectList(new LambdaQueryWrapper<CommissionAdjust>()
            .in(CommissionAdjust::getContractNo, contractNos)
            .in(CommissionAdjust::getStatus, AdjustStatus.SUBMITTED, AdjustStatus.APPROVED));
        if (pendings.isEmpty()) {
            return;
        }
        Map<String, CommissionAdjust> pendingByContract = new HashMap<>();
        for (CommissionAdjust p : pendings) {
            pendingByContract.putIfAbsent(p.getContractNo(), p);
        }
        for (CommissionContractVo row : rows) {
            CommissionAdjust pending = StringUtils.isBlank(row.getContractNo()) ? null
                : pendingByContract.get(row.getContractNo());
            if (pending == null) {
                continue;
            }
            row.setAdjustPending(true);
            row.setAdjustPendingType(pending.getAdjustType() != null ? pending.getAdjustType().getCode() : null);
            if (pending.getAdjustType() == AdjustType.AMOUNT) {
                row.setAdjustPendingAmount(pending.getNewAmount());
            }
        }
    }

    /**
     * 结佣明细折算填充：按明细自带的 bizType 批量取折算因子，计算
     * convertedAmount / expectedConvertedAmount。空集合安全。
     */
    private void fillItemDetailConversion(List<com.panjia.commission.domain.vo.CommissionItemDetailVo> details) {
        if (details == null || details.isEmpty()) {
            return;
        }
        Map<String, BigDecimal> factorMap = conversionFactorPort.factorsOf(
            details.stream()
                .map(com.panjia.commission.domain.vo.CommissionItemDetailVo::getBizType)
                .collect(Collectors.toSet()));
        for (com.panjia.commission.domain.vo.CommissionItemDetailVo d : details) {
            BigDecimal factor = conversionFactorPort.factorOf(factorMap, d.getBizType());
            if (d.getAmount() != null) {
                d.setConvertedAmount(conversionFactorPort.convert(d.getAmount(), factor));
            }
            if (d.getExpectedAmount() != null) {
                d.setExpectedConvertedAmount(conversionFactorPort.convert(d.getExpectedAmount(), factor));
            }
            // 调整前应收的折算后金额：与当前值同一因子，仅在原值存在时输出
            if (d.getOriginalExpectedAmount() != null) {
                d.setOriginalConvertedAmount(conversionFactorPort.convert(d.getOriginalExpectedAmount(), factor));
            }
            // 调整前结佣业绩的折算后金额：仅被调整行展示，未调整时原值=当前值无需输出
            if (Boolean.TRUE.equals(d.getReceivedAdjusted()) && d.getOriginalAmount() != null) {
                d.setOriginalReceivedConvertedAmount(conversionFactorPort.convert(d.getOriginalAmount(), factor));
            }
        }
    }

    public CommissionItem getItem(Long itemId) {
        return itemMapper.selectById(itemId);
    }

    public PageResult<CommissionItem> listItems(com.panjia.commission.domain.bo.CommissionItemBo query, PageQuery pageQuery) {
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

    public PageResult<CommissionConsumeLog> listConsumeLogs(com.panjia.commission.domain.bo.CommissionConsumeLogBo query,
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
        // 总监办理前 approve() 会再次更新为最新值。跳过逻辑与 updateAmountVariables 一致，
        // 覆盖总监发起时系统自动过总监节点的场景（该路径不经 approve，不会再次修改变量）。
        BigDecimal expectedAmount = application.getExpectedAmount() == null
            ? BigDecimal.ZERO : application.getExpectedAmount();
        boolean skipEnabled = Boolean.TRUE.equals(
            configService.getConfigBool(CONFIG_SKIP_FINANCE_WHEN_MATCH));
        BigDecimal realAmount = skipEnabled
            ? expectedAmount : expectedAmount.add(BigDecimal.ONE);
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
     * 总监节点办理完成后的联动（由 capp_finance 任务创建事件驱动）。
     * <p>
     * T-04 互斥网关 skip_condition 命中时直跳 capp_end，财务节点不创建，本方法不触发；
     * 仅在进入财务节点时执行：回填最近审批人/审批时间。
     * </p>
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
        // 流程进入财务节点 = 总监节点已办理：回填最近审批人/审批时间（定向更新两列，避免触碰 current_node/version）
        if (application.getStatus() == ApplicationStatus.SUBMITTED && operatorId != null) {
            LocalDateTime approvedAt = LocalDateTime.now();
            applicationMapper.update(null, new LambdaUpdateWrapper<CommissionApplication>()
                .eq(CommissionApplication::getId, applicationId)
                .set(CommissionApplication::getApproverId, operatorId)
                .set(CommissionApplication::getApproveTime, approvedAt));
        }
        refreshCurrentNode(application);
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

    /**
     * 作废已锁定单时冲销全部明细（含 APPROVED），释放业绩事实供重新发起。
     * 薪资域 findLocked 仅取 APPROVED 明细，冲销后该单不再计入工资。
     */
    private int reverseAllItems(Long applicationId) {
        return itemMapper.update(null, new LambdaUpdateWrapper<CommissionItem>()
            .eq(CommissionItem::getApplicationId, applicationId)
            .ne(CommissionItem::getStatus, ItemStatus.REVERSED)
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

    /**
     * 前端预检：该合同当月是否存在审批中的结佣申请单（SUBMITTED）。
     * <p>结佣申请按「合同 + 业绩归属月」粒度唯一，同期间同合同只允许一张在途单。
     *
     * @param period     业绩归属月
     * @param contractNo 合同号或订单号
     * @return true=存在在途申请单，前端应禁用提交
     */
    public boolean hasInFlightApplication(String period, String contractNo) {
        if (StringUtils.isBlank(period) || StringUtils.isBlank(contractNo)) {
            return false;
        }
        Long count = applicationMapper.selectCount(new LambdaQueryWrapper<CommissionApplication>()
            .eq(CommissionApplication::getPeriod, period)
            .and(w -> w.eq(CommissionApplication::getContractNo, contractNo)
                .or().eq(CommissionApplication::getOrderNo, contractNo))
            .eq(CommissionApplication::getStatus, ApplicationStatus.SUBMITTED));
        return count != null && count > 0;
    }

    /**
     * 查询该合同（跨期间）最近一张活跃申请单，防重复结佣。
     * <p>
     * 跨期口径（2026-10）：结佣期间与实收月解耦后，同一合同任何期间的活跃单
     * （DRAFT/SUBMITTED/APPROVED/LOCKED）都阻止再次发起——合同已结佣的追加走调整单。
     */
    private CommissionApplication findActiveApplication(String period, String contractNo) {
        return applicationMapper.selectOne(new LambdaQueryWrapper<CommissionApplication>()
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
     * 批量预查所有合同当月活跃+驳回申请单（P1 优化）。
     * <p>
     * 一条 IN 查询取所有输入合同号对应的活跃（DRAFT/SUBMITTED/APPROVED/LOCKED）和驳回
     * （REJECTED）申请单，按输入字符串（合同号或订单号）分别建 Map，
     * doBatchApply 和 doApply 直接取值，避免逐合同查（N×3→1 次）。
     * <p>
     * 同一合同多张单取最新一张（按 createTime DESC 排序后 putIfAbsent）。
     */
    private void loadApplicationsBatch(String period, Collection<String> contractNos, BatchApplyContext ctx) {
        if (contractNos.isEmpty()) {
            return;
        }
        // 跨期间加载（2026-10）：结佣期间与实收月解耦，同合同任何期间的活跃/驳回单都参与幂等判断
        List<CommissionApplication> all = applicationMapper.selectList(new LambdaQueryWrapper<CommissionApplication>()
            .and(w -> w.in(CommissionApplication::getContractNo, contractNos)
                .or().in(CommissionApplication::getOrderNo, contractNos))
            .in(CommissionApplication::getStatus, ApplicationStatus.DRAFT, ApplicationStatus.SUBMITTED,
                ApplicationStatus.APPROVED, ApplicationStatus.LOCKED, ApplicationStatus.REJECTED)
            .orderByDesc(CommissionApplication::getCreateTime));
        // 按真实合同号/订单号分别建索引（同键取最新一张）
        Map<String, CommissionApplication> activeByContract = new HashMap<>();
        Map<String, CommissionApplication> activeByOrder = new HashMap<>();
        Map<String, CommissionApplication> rejectedByContract = new HashMap<>();
        Map<String, CommissionApplication> rejectedByOrder = new HashMap<>();
        for (CommissionApplication app : all) {
            boolean isActive = app.getStatus() != ApplicationStatus.REJECTED;
            if (app.getContractNo() != null) {
                if (isActive) {
                    activeByContract.putIfAbsent(app.getContractNo(), app);
                } else {
                    rejectedByContract.putIfAbsent(app.getContractNo(), app);
                }
            }
            if (app.getOrderNo() != null) {
                if (isActive) {
                    activeByOrder.putIfAbsent(app.getOrderNo(), app);
                } else {
                    rejectedByOrder.putIfAbsent(app.getOrderNo(), app);
                }
            }
        }
        // 按输入字符串（可能是合同号或订单号）匹配到对应申请单
        for (String input : contractNos) {
            ctx.activeApps.put(input, activeByContract.getOrDefault(input, activeByOrder.get(input)));
            ctx.rejectedApps.put(input, rejectedByContract.getOrDefault(input, rejectedByOrder.get(input)));
        }
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
     * 由事实构建结佣明细（不折算；冻结 contractNo/employee/dept/bizType/roleType）。
     * <p>
     * period 强制 = 申请单期间：薪资域 findLocked 按 {@code CommissionItem.period} 取数，
     * 明细绑定的可能是早于申请单月份的新签事实（跨月），明细期间必须与申请单一致。
     *
     * @param commissionAmount 结佣金额 = 绑定新签事实当前金额（调整后）
     */
    private CommissionItem buildItem(CommissionApplication application, PerformanceFactSummaryDTO fact,
                                     BigDecimal commissionAmount, Long adjustId) {
        CommissionItem item = new CommissionItem();
        item.setApplicationId(application.getId());
        item.setPerformanceFactId(fact.getFactId());
        item.setFactType(fact.getFactType());
        item.setContractNo(application.getContractNo());
        // 冻结事实展示快照：工资明细/导出直接取本表，免跨域 JOIN 事实表
        item.setOrderNo(fact.getOrderNo());
        item.setBusinessDate(fact.getBusinessDate());
        item.setPropertyAddress(fact.getPropertyAddress());
        item.setShareRatio(fact.getShareRatio());
        item.setEmployeeCode(fact.getEmployeeCode());
        item.setRoleName(fact.getRoleName());
        item.setSourceKey(fact.getSourceKey());
        item.setBatchId(fact.getBatchId());
        item.setSource(fact.getSource() != null ? fact.getSource() : "IMPORT");
        item.setReceivedApplyId(fact.getReceivedApplyId());
        item.setPeriod(application.getPeriod());
        item.setEmployeeId(fact.getEmployeeId());
        item.setDeptId(fact.getDeptId());
        item.setBizType(fact.getBizType());
        item.setRoleType(fact.getRoleType());
        item.setAmount(commissionAmount);
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
