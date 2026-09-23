package com.panjia.commission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.panjia.commission.domain.AdjustStatus;
import com.panjia.commission.domain.AdjustType;
import com.panjia.commission.domain.ApplicationStatus;
import com.panjia.commission.domain.CommissionAdjust;
import com.panjia.commission.domain.CommissionApplication;
import com.panjia.commission.domain.CommissionItem;
import com.panjia.commission.domain.ItemStatus;
import com.panjia.commission.domain.ReversedReason;
import com.panjia.commission.domain.bo.CommissionAdjustCreateBo;
import com.panjia.commission.domain.bo.CommissionAdjustBo;
import com.panjia.commission.mapper.CommissionAdjustMapper;
import com.panjia.commission.mapper.CommissionItemMapper;
import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ApprovalStartCmd;
import com.panjia.contracts.port.CommissionPerformanceQueryPort;
import com.panjia.contracts.port.ConversionFactorPort;
import com.panjia.contracts.port.PeriodCloseQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 结佣调整单服务（DISCOUNT / DIFF / VOID，结佣域详细设计 §4.5）。
 * <p>
 * 已审批结佣数据变更的唯一入口（V4.2 §9.4），不自动修复。EXECUTED 同事务动作：
 * <ul>
 *   <li>DISCOUNT：旧明细 REVERSED（reason=MANUAL_ADJUST）+ 新明细（amount=折后值直接存储，
 *       performance_fact_id 沿用原值，命中 uk_citem_fact_active 排除 REVERSED 的部分唯一索引）；</li>
 *   <li>DIFF：新增差额明细（performance_fact_id = NULL，period = target_period）；</li>
 *   <li>VOID：旧明细 REVERSED。</li>
 * </ul>
 * <p>
 * 折扣不存系数：发起 DISCOUNT 时算好折后值直接存 {@code amount = 8500}，reason 写"85折"供审计，
 * 系统不认识"系数"这个概念（§2.2）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionAdjustService {

    private static final DateTimeFormatter ADJUST_NO_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** 工作流状态：审批通过 */
    private static final String WF_STATUS_FINISH = "finish";
    /** 工作流状态：作废 */
    private static final String WF_STATUS_INVALID = "invalid";
    /** 工作流状态：终止 */
    private static final String WF_STATUS_TERMINATION = "termination";
    /** 工作流状态：撤销 */
    private static final String WF_STATUS_CANCEL = "cancel";

    private final CommissionAdjustMapper adjustMapper;
    private final CommissionItemMapper itemMapper;
    /** 折算比例唯一来源：契约层端口（规则表由薪酬域持有，本域不直连） */
    private final ConversionFactorPort conversionFactorPort;
    private final CommissionApplicationService applicationService;
    private final PeriodCloseQueryPort periodCloseQueryPort;
    private final ObjectMapper objectMapper;
    private final ApprovalPort approvalPort;
    /** 业绩事实跨域端口：结佣调整直接操作 PERF_REAL + PERF_EXPECT 事实 */
    private final CommissionPerformanceQueryPort performanceQueryPort;

    private static final String FACT_TYPE_REAL = "PERF_REAL";
    private static final String FACT_TYPE_EXPECT = "PERF_EXPECT";
    private static final String SCOPE_CONTRACT = "CONTRACT";
    private static final String SCOPE_DETAIL = "DETAIL";

    // ==================== 发起 ====================

    /**
     * 发起调整单（SUBMITTED，待审批）。
     * <p>
     * 前置校验：① 申请单状态必须为 LOCKED（结佣锁定后方可调整）；
     * ② 明细级 item 属于该申请单；③ 同一对象无未完成调整单；④ 封账校验。
     *
     * @param dto        调整创建条件（期间/合同号/调整类型/金额/原因等）
     * @param operatorId 发起人 ID
     * @return 调整单
     */
    @Transactional(rollbackFor = Exception.class)
    public CommissionAdjust create(CommissionAdjustCreateBo dto, Long operatorId) {
        AdjustType adjustType = AdjustType.fromCode(dto.getAdjustType());
        if (adjustType == null) {
            throw new ServiceException("非法调整类型：" + dto.getAdjustType());
        }
        boolean detailScope = SCOPE_DETAIL.equals(dto.getAdjustScope());
        CommissionApplication application = applicationService.getApplication(dto.getApplicationId());
        if (application.getStatus() != ApplicationStatus.LOCKED) {
            throw new ServiceException("仅已锁定（LOCKED）的结佣申请单可发起调整（当前："
                + application.getStatus().getDesc() + "）");
        }

        CommissionItem item = null;
        if (detailScope) {
            if (dto.getItemId() == null) {
                throw new ServiceException("明细级调整必须指定结佣明细 ID");
            }
            item = itemMapper.selectById(dto.getItemId());
            if (item == null) {
                throw new ServiceException("结佣明细不存在：" + dto.getItemId());
            }
            if (!item.getApplicationId().equals(application.getId())) {
                throw new ServiceException("结佣明细不属于该申请单：itemId=" + item.getId()
                    + ", applicationId=" + application.getId());
            }
        }

        // ① 封账校验（按申请单期间）
        checkPeriodOpen(application.getPeriod(), adjustType.getDesc());

        // ② 类型参数校验
        if (adjustType == AdjustType.AMOUNT) {
            if (dto.getTargetAmount() == null || dto.getTargetAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw new ServiceException("金额调整必须指定调整后金额（targetAmount ≥ 0）");
            }
        } else if (adjustType == AdjustType.TRANSFER) {
            if (dto.getTargetDeptId() == null) {
                throw new ServiceException("部门划转必须指定目标部门");
            }
        }

        // ③ 同一对象无未完成调整单（明细级按 itemId，合同级按 applicationId）
        LambdaQueryWrapper<CommissionAdjust> unfinishedWrapper = new LambdaQueryWrapper<CommissionAdjust>()
            .in(CommissionAdjust::getStatus, AdjustStatus.SUBMITTED, AdjustStatus.APPROVED);
        if (detailScope) {
            unfinishedWrapper.eq(CommissionAdjust::getItemId, item.getId());
        } else {
            unfinishedWrapper.eq(CommissionAdjust::getApplicationId, application.getId())
                .isNull(CommissionAdjust::getItemId);
        }
        Long unfinished = adjustMapper.selectCount(unfinishedWrapper);
        if (unfinished != null && unfinished > 0) {
            throw new ServiceException("该对象已有未完成调整单，请先完成审批后再发起新调整");
        }

        // 计算调整前金额与差额
        BigDecimal originalAmount;
        Long factId = null;
        if (detailScope) {
            originalAmount = item.getAmount();
            factId = item.getPerformanceFactId();
        } else {
            originalAmount = sumFacts(application.getPeriod(), application.getContractNo(), FACT_TYPE_REAL);
        }
        BigDecimal targetAmount = dto.getTargetAmount();
        BigDecimal deltaAmount = targetAmount == null ? null
            : targetAmount.subtract(originalAmount == null ? BigDecimal.ZERO : originalAmount);

        CommissionAdjust adjust = new CommissionAdjust();
        adjust.setAdjustNo("CADJ" + LocalDateTime.now().format(ADJUST_NO_FORMATTER));
        adjust.setApplicationId(application.getId());
        adjust.setItemId(detailScope ? item.getId() : null);
        adjust.setPeriod(application.getPeriod());
        adjust.setAdjustType(adjustType);
        adjust.setNewAmount(targetAmount);            // 复用 new_amount 列：调整后金额
        adjust.setDiffAmount(deltaAmount);            // 复用 diff_amount 列：调整差额
        adjust.setOriginalAmount(originalAmount);
        adjust.setContractNo(application.getContractNo());
        adjust.setFactType(FACT_TYPE_REAL);
        adjust.setAdjustScope(dto.getAdjustScope());
        adjust.setFactId(factId);
        adjust.setTargetDeptId(dto.getTargetDeptId());
        adjust.setReason(dto.getReason());
        adjust.setStatus(AdjustStatus.SUBMITTED);
        adjust.setApplicantId(operatorId);
        adjustMapper.insert(adjust);

        // 发起审批流程（bizType=COMMISSION_ADJUST，businessId=调整单ID），失败则整体回滚
        ApprovalStartCmd cmd = buildStartCmd(adjust);
        boolean started;
        try {
            started = approvalPort.startAndCompleteFirst(BizType.COMMISSION_ADJUST, adjust.getId(), cmd);
        } catch (Exception e) {
            log.error("[结佣-调整] 审批流程发起异常：adjustId={}", adjust.getId(), e);
            throw new ServiceException("结佣调整审批流程发起失败：" + e.getMessage());
        }
        if (!started) {
            throw new ServiceException("结佣调整审批流程发起失败");
        }

        try {
            Long instanceId = approvalPort.instanceId(BizType.COMMISSION_ADJUST, adjust.getId());
            if (instanceId != null) {
                adjust.setProcessInstanceId(String.valueOf(instanceId));
                adjustMapper.updateById(adjust);
            }
        } catch (Exception e) {
            log.warn("[结佣-调整] 流程实例ID回填失败：adjustId={}", adjust.getId(), e);
        }

        log.info("[结佣-调整] 调整单已发起并提交审批：adjustNo={}, type={}, scope={}, applicantId={}",
            adjust.getAdjustNo(), adjustType.getCode(), dto.getAdjustScope(), operatorId);
        return adjust;
    }

    /** 按期间+合同号求指定口径 ACTIVE 事实金额合计。 */
    private BigDecimal sumFacts(String period, String contractNo, String factType) {
        List<PerformanceFactSummaryDTO> facts = performanceQueryPort.findActiveByContract(period, contractNo, factType);
        if (facts == null || facts.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return facts.stream()
            .map(f -> f.getAmount() == null ? BigDecimal.ZERO : f.getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ==================== 工作流回调 ====================

    /**
     * 工作流审批回调（由 CommissionAdjustWorkflowListener 驱动）。
     * <p>
     * 状态映射：
     * <ul>
     *   <li>finish（审批通过）→ SUBMITTED → EXECUTED，同事务执行明细变更；</li>
     *   <li>invalid / termination（作废/终止）→ REJECTED；</li>
     *   <li>cancel（撤销）→ CANCELLED。</li>
     * </ul>
     * 幂等：非 SUBMITTED 状态的回调直接忽略。
     *
     * @param adjustId 调整单 ID（businessId）
     * @param status   流程状态（finish / invalid / termination / cancel）
     * @param handler  办理人 ID
     * @param message  审批意见
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleWorkflowEvent(Long adjustId, String status, String handler, String message) {
        CommissionAdjust adjust = adjustMapper.selectById(adjustId);
        if (adjust == null) {
            log.warn("[结佣-调整工作流] 调整单不存在，忽略回调：adjustId={}, status={}", adjustId, status);
            return;
        }
        Long handlerId = parseHandlerId(handler);

        switch (status == null ? "" : status) {
            case WF_STATUS_FINISH -> {
                if (adjust.getStatus() != AdjustStatus.SUBMITTED) {
                    log.info("[结佣-调整工作流] 非提交态，忽略通过回调：adjustId={}, current={}",
                        adjustId, adjust.getStatus());
                    return;
                }
                log.info("[结佣-调整工作流] 审批通过，执行调整：adjustId={}, handler={}, message={}",
                    adjustId, handler, message);
                executeAndMark(adjust, handlerId);
            }
            case WF_STATUS_INVALID, WF_STATUS_TERMINATION -> {
                if (adjust.getStatus() != AdjustStatus.SUBMITTED) {
                    return;
                }
                adjust.setStatus(AdjustStatus.REJECTED);
                adjust.setApproverId(handlerId);
                adjustMapper.updateById(adjust);
                log.info("[结佣-调整工作流] 流程作废/终止，调整单置 REJECTED：adjustId={}, message={}", adjustId, message);
            }
            case WF_STATUS_CANCEL -> {
                if (adjust.getStatus() != AdjustStatus.SUBMITTED) {
                    return;
                }
                adjust.setStatus(AdjustStatus.CANCELLED);
                adjustMapper.updateById(adjust);
                log.info("[结佣-调整工作流] 流程撤销，调整单置 CANCELLED：adjustId={}, message={}", adjustId, message);
            }
            default -> log.info("[结佣-调整工作流] 无需处理的状态，忽略：adjustId={}, status={}", adjustId, status);
        }
    }

    /**
     * 构建审批启动命令（业务编码/标题 + 流程变量），供适配器转译为引擎原生 StartProcessDTO + bizExt。
     */
    private ApprovalStartCmd buildStartCmd(CommissionAdjust adjust) {
        ApprovalStartCmd cmd = ApprovalStartCmd.of(
            text(adjust.getAdjustNo()),
            "结佣调整｜账期" + text(adjust.getPeriod())
                + "｜类型" + text(adjust.getAdjustType())
                + "｜差额" + text(adjust.getDiffAmount())
                + "｜单号" + text(adjust.getAdjustNo()));
        Map<String, Object> variables = new HashMap<>(2);
        // 后端发起无登录用户上下文，忽略权限
        variables.put("ignore", true);
        cmd.setVariables(variables);
        return cmd;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 标记 EXECUTED 并按类型执行明细变更（同事务）。
     */
    private void executeAndMark(CommissionAdjust adjust, Long approverId) {
        adjust.setStatus(AdjustStatus.EXECUTED);
        adjust.setApproverId(approverId);
        int rows = adjustMapper.updateById(adjust);
        if (rows == 0) {
            throw new ServiceException("调整单并发冲突，请重试：adjustId=" + adjust.getId());
        }

        AdjustType type = adjust.getAdjustType();
        switch (type) {
            case AMOUNT -> executeAmountAdjust(adjust, approverId);
            case VOID -> executeVoidAdjust(adjust, approverId);
            case TRANSFER -> executeTransferAdjust(adjust, approverId);
            default -> throw new ServiceException("非法调整类型：" + type);
        }
        log.info("[结佣-调整] 调整单已执行：adjustNo={}, type={}, scope={}",
            adjust.getAdjustNo(), type.getCode(), adjust.getAdjustScope());
    }

    /**
     * 解析工作流回调办理人 ID（params.handler 为用户 ID 字符串，解析失败返回 null）。
     */
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

    // ==================== 查询 ====================

    /**
     * 分页查询调整单。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 调整单分页
     */
    public PageResult<CommissionAdjust> listAdjusts(CommissionAdjustBo query, PageQuery pageQuery) {
        LambdaQueryWrapper<CommissionAdjust> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.isNotBlank(query.getPeriod()), CommissionAdjust::getPeriod, query.getPeriod())
            .eq(query.getApplicationId() != null, CommissionAdjust::getApplicationId, query.getApplicationId())
            .eq(StringUtils.isNotBlank(query.getAdjustType()), CommissionAdjust::getAdjustType,
                AdjustType.fromCode(query.getAdjustType()))
            .eq(StringUtils.isNotBlank(query.getStatus()), CommissionAdjust::getStatus,
                AdjustStatus.fromCode(query.getStatus()))
            .like(StringUtils.isNotBlank(query.getKeyword()), CommissionAdjust::getContractNo, query.getKeyword())
            .orderByDesc(CommissionAdjust::getCreateTime);

        // employeeId / bizType 过滤：通过 CommissionItem 反查 itemId 集合
        if (query.getEmployeeId() != null || StringUtils.isNotBlank(query.getBizType())) {
            LambdaQueryWrapper<CommissionItem> itemWrapper = new LambdaQueryWrapper<>();
            if (query.getEmployeeId() != null) {
                itemWrapper.eq(CommissionItem::getEmployeeId, query.getEmployeeId());
            }
            if (StringUtils.isNotBlank(query.getBizType())) {
                itemWrapper.eq(CommissionItem::getBizType, query.getBizType());
            }
            List<CommissionItem> items = itemMapper.selectList(itemWrapper);
            Set<Long> itemIds = items.stream().map(CommissionItem::getId).collect(Collectors.toSet());
            if (itemIds.isEmpty()) {
                return PageResult.build(List.of(), 0);
            }
            Set<Long> appIds = items.stream()
                .map(CommissionItem::getApplicationId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
            // 明细级调整按 itemId 命中，合同级调整（itemId IS NULL）按 applicationId 命中
            wrapper.and(w -> w
                .in(CommissionAdjust::getItemId, itemIds)
                .or()
                .isNull(CommissionAdjust::getItemId)
                .in(CommissionAdjust::getApplicationId, appIds));
        }

        // deptId 过滤：通过 CommissionItem 的 deptId 反查
        if (query.getDeptId() != null) {
            LambdaQueryWrapper<CommissionItem> deptWrapper = new LambdaQueryWrapper<>();
            deptWrapper.eq(CommissionItem::getDeptId, query.getDeptId());
            List<CommissionItem> deptItems = itemMapper.selectList(deptWrapper);
            Set<Long> deptItemIds = deptItems.stream().map(CommissionItem::getId).collect(Collectors.toSet());
            if (deptItemIds.isEmpty()) {
                return PageResult.build(List.of(), 0);
            }
            Set<Long> deptAppIds = deptItems.stream()
                .map(CommissionItem::getApplicationId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
            wrapper.and(w -> w
                .in(CommissionAdjust::getItemId, deptItemIds)
                .or()
                .isNull(CommissionAdjust::getItemId)
                .in(CommissionAdjust::getApplicationId, deptAppIds));
        }

        var page = adjustMapper.selectPage(pageQuery.build(), wrapper);
        List<CommissionAdjust> records = page.getRecords();
        fillConvertedAmounts(records);
        return PageResult.build(records, page.getTotal());
    }

    /**
     * 调整单详情。
     *
     * @param adjustId 调整单 ID
     * @return 调整单
     */
    public CommissionAdjust getAdjust(Long adjustId) {
        CommissionAdjust adjust = adjustMapper.selectById(adjustId);
        if (adjust == null) {
            throw new ServiceException("结佣调整单不存在：" + adjustId);
        }
        fillConvertedAmounts(List.of(adjust));
        return adjust;
    }

    /**
     * 批量填充折算后金额（列表 / 详情展示用，不入库）。
     * <p>
     * 调整单本身不存 bizType，按 itemId 批量反查结佣明细的业务类型；折扣（DISCOUNT）取
     * newAmount 折算值，差额补发（DIFF）取 diffAmount 折算值；原值为空则折算值保持 null，
     * 让前端显示占位符而不是 0.00。
     */
    private void fillConvertedAmounts(List<CommissionAdjust> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Set<Long> itemIds = new HashSet<>();
        for (CommissionAdjust r : records) {
            if (r.getItemId() != null) {
                itemIds.add(r.getItemId());
            }
        }
        Map<Long, String> bizTypeByItem = itemBizTypes(itemIds);
        for (CommissionAdjust r : records) {
            String bizType = r.getItemId() == null ? null : bizTypeByItem.get(r.getItemId());
            BigDecimal factor = conversionFactorPort.factorOf(bizType);
            if (r.getNewAmount() != null) {
                r.setConvertedNewAmount(conversionFactorPort.convert(r.getNewAmount(), factor));
            }
            if (r.getDiffAmount() != null) {
                r.setConvertedDiffAmount(conversionFactorPort.convert(r.getDiffAmount(), factor));
            }
        }
    }

    /** 按明细 ID 批量查业务类型（itemId → bizType）。 */
    private Map<Long, String> itemBizTypes(Set<Long> itemIds) {
        Map<Long, String> map = new HashMap<>();
        if (itemIds.isEmpty()) {
            return map;
        }
        for (Map<String, Object> row : itemMapper.selectBizTypeByItemIds(itemIds)) {
            Object id = row.get("itemId");
            if (id == null) {
                continue;
            }
            map.put(((Number) id).longValue(), row.get("bizType") == null ? null : String.valueOf(row.get("bizType")));
        }
        return map;
    }

    // ==================== EXECUTED 同事务动作 ====================

    /**
     * 金额调整（AMOUNT）：同步调整 PERF_REAL（结佣）与 PERF_EXPECT（应收），应用同一绝对差额。
     * <ul>
     *   <li>合同级：按金额占比分摊差额到各 PERF_REAL 事实；PERF_EXPECT 合计 + 同一差额并按占比分摊；
     *       回写所有 CommissionItem 的 amount / performance_fact_id；</li>
     *   <li>明细级：单条 PERF_REAL 事实 supersede 为 targetAmount；匹配同员工 PERF_EXPECT 事实 +delta；
     *       回写该 CommissionItem。</li>
     * </ul>
     */
    private void executeAmountAdjust(CommissionAdjust adjust, Long approverId) {
        BigDecimal targetAmount = adjust.getNewAmount();
        BigDecimal delta = adjust.getDiffAmount();
        if (SCOPE_CONTRACT.equals(adjust.getAdjustScope())) {
            // 1. PERF_REAL 合同级分摊调整
            Map<Long, Long> realMapping = performanceQueryPort.adjustContractFactsAmount(
                adjust.getPeriod(), adjust.getContractNo(), FACT_TYPE_REAL, targetAmount, approverId, adjust.getId());
            // 2. PERF_EXPECT 同步同一差额
            BigDecimal currentExpect = sumFacts(adjust.getPeriod(), adjust.getContractNo(), FACT_TYPE_EXPECT);
            BigDecimal targetExpect = currentExpect.add(delta == null ? BigDecimal.ZERO : delta);
            performanceQueryPort.adjustContractFactsAmount(
                adjust.getPeriod(), adjust.getContractNo(), FACT_TYPE_EXPECT, targetExpect, approverId, adjust.getId());
            // 3. 回写 CommissionItem：amount 与 performance_fact_id 指向新事实
            List<CommissionItem> items = listActiveItems(adjust.getApplicationId());
            for (CommissionItem item : items) {
                Long newFactId = realMapping.get(item.getPerformanceFactId());
                if (newFactId != null) {
                    item.setPerformanceFactId(newFactId);
                    item.setAdjustId(adjust.getId());
                }
            }
            // amount 从新事实批量回查
            if (!items.isEmpty()) {
                Map<Long, BigDecimal> amountByFact = loadFactAmounts(
                    items.stream().map(CommissionItem::getPerformanceFactId).toList());
                for (CommissionItem item : items) {
                    BigDecimal amt = amountByFact.get(item.getPerformanceFactId());
                    if (amt != null) {
                        item.setAmount(amt);
                    }
                    itemMapper.updateById(item);
                }
            }
            applicationService.recalcAggregates(adjust.getApplicationId(), null);
            log.info("[结佣-调整-AMOUNT-合同级] 完成：adjustId={}, delta={}, realFacts={}",
                adjust.getId(), delta, realMapping.size());
        } else {
            // 明细级
            CommissionItem item = itemMapper.selectById(adjust.getItemId());
            requireItem(item, adjust);
            // 1. PERF_REAL 单条 supersede
            Long newRealFactId = performanceQueryPort.adjustFactAmount(
                item.getPerformanceFactId(), targetAmount, approverId, adjust.getId());
            // 2. PERF_EXPECT 同步同一差额（同合同+同员工匹配）
            PerformanceFactSummaryDTO expectFact = findExpectByEmployee(
                adjust.getPeriod(), adjust.getContractNo(), item.getEmployeeId());
            if (expectFact != null) {
                BigDecimal expectTarget = expectFact.getAmount().add(delta == null ? BigDecimal.ZERO : delta);
                performanceQueryPort.adjustFactAmount(
                    expectFact.getFactId(), expectTarget, approverId, adjust.getId());
            }
            // 3. 回写 CommissionItem
            item.setAmount(targetAmount);
            if (newRealFactId != null) {
                item.setPerformanceFactId(newRealFactId);
            }
            item.setAdjustId(adjust.getId());
            itemMapper.updateById(item);
            applicationService.recalcAggregates(adjust.getApplicationId(), null);
            log.info("[结佣-调整-AMOUNT-明细级] 完成：adjustId={}, itemId={}, delta={}",
                adjust.getId(), item.getId(), delta);
        }
    }

    /**
     * 业绩冲销（VOID）：冲销 PERF_REAL 事实并同步冲销 PERF_EXPECT，结佣明细置 REVERSED。
     */
    private void executeVoidAdjust(CommissionAdjust adjust, Long approverId) {
        if (SCOPE_CONTRACT.equals(adjust.getAdjustScope())) {
            // 合同级：冲销该合同全部 PERF_REAL 与 PERF_EXPECT 事实
            for (PerformanceFactSummaryDTO f : performanceQueryPort.findActiveByContract(
                adjust.getPeriod(), adjust.getContractNo(), FACT_TYPE_REAL)) {
                performanceQueryPort.voidFact(f.getFactId(), approverId, adjust.getId());
            }
            for (PerformanceFactSummaryDTO f : performanceQueryPort.findActiveByContract(
                adjust.getPeriod(), adjust.getContractNo(), FACT_TYPE_EXPECT)) {
                performanceQueryPort.voidFact(f.getFactId(), approverId, adjust.getId());
            }
            for (CommissionItem item : listActiveItems(adjust.getApplicationId())) {
                reverseItem(item, adjust.getId());
            }
            applicationService.recalcAggregates(adjust.getApplicationId(), null);
            log.info("[结佣-调整-VOID-合同级] 完成：adjustId={}", adjust.getId());
        } else {
            CommissionItem item = itemMapper.selectById(adjust.getItemId());
            requireItem(item, adjust);
            performanceQueryPort.voidFact(item.getPerformanceFactId(), approverId, adjust.getId());
            PerformanceFactSummaryDTO expectFact = findExpectByEmployee(
                adjust.getPeriod(), adjust.getContractNo(), item.getEmployeeId());
            if (expectFact != null) {
                performanceQueryPort.voidFact(expectFact.getFactId(), approverId, adjust.getId());
            }
            reverseItem(item, adjust.getId());
            applicationService.recalcAggregates(adjust.getApplicationId(), null);
            log.info("[结佣-调整-VOID-明细级] 完成：adjustId={}, itemId={}", adjust.getId(), item.getId());
        }
    }

    /**
     * 部门划转（TRANSFER）：划转 PERF_REAL 事实部门并同步 PERF_EXPECT，回写 CommissionItem.deptId。
     */
    private void executeTransferAdjust(CommissionAdjust adjust, Long approverId) {
        Long targetDeptId = adjust.getTargetDeptId();
        if (SCOPE_CONTRACT.equals(adjust.getAdjustScope())) {
            for (PerformanceFactSummaryDTO f : performanceQueryPort.findActiveByContract(
                adjust.getPeriod(), adjust.getContractNo(), FACT_TYPE_REAL)) {
                performanceQueryPort.transferFact(f.getFactId(), targetDeptId, approverId, adjust.getId());
            }
            for (PerformanceFactSummaryDTO f : performanceQueryPort.findActiveByContract(
                adjust.getPeriod(), adjust.getContractNo(), FACT_TYPE_EXPECT)) {
                performanceQueryPort.transferFact(f.getFactId(), targetDeptId, approverId, adjust.getId());
            }
            for (CommissionItem item : listActiveItems(adjust.getApplicationId())) {
                item.setDeptId(targetDeptId);
                item.setAdjustId(adjust.getId());
                itemMapper.updateById(item);
            }
            log.info("[结佣-调整-TRANSFER-合同级] 完成：adjustId={}, targetDeptId={}", adjust.getId(), targetDeptId);
        } else {
            CommissionItem item = itemMapper.selectById(adjust.getItemId());
            requireItem(item, adjust);
            performanceQueryPort.transferFact(item.getPerformanceFactId(), targetDeptId, approverId, adjust.getId());
            PerformanceFactSummaryDTO expectFact = findExpectByEmployee(
                adjust.getPeriod(), adjust.getContractNo(), item.getEmployeeId());
            if (expectFact != null) {
                performanceQueryPort.transferFact(expectFact.getFactId(), targetDeptId, approverId, adjust.getId());
            }
            item.setDeptId(targetDeptId);
            item.setAdjustId(adjust.getId());
            itemMapper.updateById(item);
            log.info("[结佣-调整-TRANSFER-明细级] 完成：adjustId={}, itemId={}, targetDeptId={}",
                adjust.getId(), item.getId(), targetDeptId);
        }
    }

    /** 查申请单下非 REVERSED 的结佣明细。 */
    private List<CommissionItem> listActiveItems(Long applicationId) {
        return itemMapper.selectList(new LambdaQueryWrapper<CommissionItem>()
            .eq(CommissionItem::getApplicationId, applicationId)
            .ne(CommissionItem::getStatus, ItemStatus.REVERSED));
    }

    /** 按事实 ID 批量查当前金额（回写 CommissionItem.amount 用）。 */
    private Map<Long, BigDecimal> loadFactAmounts(List<Long> factIds) {
        Map<Long, BigDecimal> map = new HashMap<>();
        if (factIds == null || factIds.isEmpty()) {
            return map;
        }
        for (PerformanceFactSummaryDTO f : performanceQueryPort.findActiveByFacts(factIds)) {
            map.put(f.getFactId(), f.getAmount());
        }
        return map;
    }

    /** 按同合同+同员工匹配 PERF_EXPECT 事实（明细级同步用）。 */
    private PerformanceFactSummaryDTO findExpectByEmployee(String period, String contractNo, Long employeeId) {
        if (employeeId == null) {
            return null;
        }
        return performanceQueryPort.findActiveByContract(period, contractNo, FACT_TYPE_EXPECT).stream()
            .filter(f -> employeeId.equals(f.getEmployeeId()))
            .findFirst().orElse(null);
    }

    /** 结佣明细置 REVERSED。 */
    private void reverseItem(CommissionItem item, Long adjustId) {
        item.setStatus(ItemStatus.REVERSED);
        item.setReversedReason(ReversedReason.MANUAL_ADJUST);
        item.setAdjustId(adjustId);
        itemMapper.updateById(item);
    }

    // ==================== 内部方法 ====================

    private void checkPeriodOpen(String period, String action) {
        if (periodCloseQueryPort.isClosed(period)) {
            throw new ServiceException("[" + action + "] 期间 " + period + " 已封账，结佣窗口关闭，拒绝执行");
        }
    }

    private void requireItem(CommissionItem item, CommissionAdjust adjust) {
        if (item == null) {
            throw new ServiceException("调整对象明细不存在：itemId=" + adjust.getItemId());
        }
        if (item.getStatus() == ItemStatus.REVERSED) {
            throw new ServiceException("明细已被冲销（终态），调整单不可重复执行：itemId=" + item.getId());
        }
    }
}
