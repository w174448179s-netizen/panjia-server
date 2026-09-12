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
import com.panjia.commission.dto.AdjustCreateDTO;
import com.panjia.commission.dto.AdjustQuery;
import com.panjia.commission.mapper.CommissionAdjustMapper;
import com.panjia.commission.mapper.CommissionItemMapper;
import com.panjia.contracts.port.PeriodCloseQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.workflow.api.WorkflowService;
import org.dromara.workflow.api.domain.StartProcessDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 结佣调整审批流编码（flow_definition.flow_code） */
    private static final String FLOW_CODE_COMMISSION_ADJUST = "commission_adjust";

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
    private final CommissionApplicationService applicationService;
    private final PeriodCloseQueryPort periodCloseQueryPort;
    private final ObjectMapper objectMapper;
    private final WorkflowService workflowService;

    // ==================== 发起 ====================

    /**
     * 发起调整单（SUBMITTED，待审批）。
     * <p>
     * 前置校验（§3.3）：① 封账校验（按 §2.5 三条判定规则，经 Port 实时查）；
     * ② 原明细为 PENDING 或 APPROVED；③ 同一 item 无未完成调整单。
     *
     * @param dto        调整单创建请求
     * @param operatorId 发起人 ID
     * @return 调整单
     */
    @Transactional(rollbackFor = Exception.class)
    public CommissionAdjust create(AdjustCreateDTO dto, Long operatorId) {
        AdjustType adjustType = AdjustType.fromCode(dto.getAdjustType());
        if (adjustType == null) {
            throw new ServiceException("非法调整类型：" + dto.getAdjustType());
        }
        CommissionApplication application = applicationService.getApplication(dto.getApplicationId());
        CommissionItem item = itemMapper.selectById(dto.getItemId());
        if (item == null) {
            throw new ServiceException("结佣明细不存在：" + dto.getItemId());
        }
        if (!item.getApplicationId().equals(application.getId())) {
            throw new ServiceException("结佣明细不属于该申请单：itemId=" + item.getId()
                + ", applicationId=" + application.getId());
        }
        if (item.getStatus() != ItemStatus.PENDING && item.getStatus() != ItemStatus.APPROVED) {
            throw new ServiceException("仅待审批/已审批明细可发起调整（当前：" + item.getStatus().getDesc() + "）");
        }

        // ① 封账校验（§2.5 判定规则）：DIFF 校验 target_period；DISCOUNT/VOID 校验明细自身 period
        if (adjustType == AdjustType.DIFF) {
            if (StringUtils.isBlank(dto.getTargetPeriod())) {
                throw new ServiceException("差额补发必须指定补发目标月（target_period）");
            }
            if (dto.getDiffAmount() == null || dto.getDiffAmount().compareTo(BigDecimal.ZERO) == 0) {
                throw new ServiceException("差额补发必须指定差额金额（diff_amount，正补负扣）");
            }
            checkPeriodOpen(dto.getTargetPeriod(), "差额补发（DIFF）");
        } else {
            checkPeriodOpen(item.getPeriod(), adjustType == AdjustType.DISCOUNT ? "折扣（DISCOUNT）" : "作废（VOID）");
        }
        if (adjustType == AdjustType.DISCOUNT
            && (dto.getNewAmount() == null || dto.getNewAmount().compareTo(BigDecimal.ZERO) < 0)) {
            throw new ServiceException("折扣调整必须指定折后最终金额（new_amount ≥ 0，直接存折后值，非系数）");
        }

        // ③ 同一明细无未完成调整单
        Long unfinished = adjustMapper.selectCount(new LambdaQueryWrapper<CommissionAdjust>()
            .eq(CommissionAdjust::getItemId, item.getId())
            .in(CommissionAdjust::getStatus, AdjustStatus.SUBMITTED, AdjustStatus.APPROVED));
        if (unfinished != null && unfinished > 0) {
            throw new ServiceException("该明细已有未完成调整单，请先完成审批后再发起新调整");
        }

        CommissionAdjust adjust = new CommissionAdjust();
        adjust.setAdjustNo("CADJ" + LocalDateTime.now().format(ADJUST_NO_FORMATTER));
        adjust.setApplicationId(application.getId());
        adjust.setItemId(item.getId());
        adjust.setPeriod(item.getPeriod());
        adjust.setAdjustType(adjustType);
        adjust.setNewAmount(dto.getNewAmount());
        adjust.setDiffAmount(dto.getDiffAmount());
        adjust.setTargetPeriod(dto.getTargetPeriod());
        adjust.setPayloadJson(buildPayload(item, dto));
        adjust.setReason(dto.getReason());
        adjust.setStatus(AdjustStatus.SUBMITTED);
        adjust.setApplicantId(operatorId);
        adjustMapper.insert(adjust);

        // 发起 RuoYi 工作流审批（businessId=调整单ID），失败则整体回滚
        StartProcessDTO startProcess = new StartProcessDTO();
        startProcess.setBusinessId(String.valueOf(adjust.getId()));
        startProcess.setFlowCode(FLOW_CODE_COMMISSION_ADJUST);
        Map<String, Object> variables = new HashMap<>(2);
        // 后端发起无登录用户上下文，忽略权限
        variables.put("ignore", true);
        startProcess.setVariables(variables);

        boolean started;
        try {
            started = workflowService.startCompleteTask(startProcess);
        } catch (Exception e) {
            log.error("[结佣-调整] 审批流程发起异常：adjustId={}", adjust.getId(), e);
            throw new ServiceException("结佣调整审批流程发起失败：" + e.getMessage());
        }
        if (!started) {
            throw new ServiceException("结佣调整审批流程发起失败");
        }

        // 回填流程实例 ID（回调以 businessId 路由，回填失败不阻断主流程）
        try {
            Long instanceId = workflowService.getInstanceIdByBusinessId(String.valueOf(adjust.getId()));
            if (instanceId != null) {
                adjust.setProcessInstanceId(String.valueOf(instanceId));
                adjustMapper.updateById(adjust);
            }
        } catch (Exception e) {
            log.warn("[结佣-调整] 流程实例ID回填失败：adjustId={}", adjust.getId(), e);
        }

        log.info("[结佣-调整] 调整单已发起并提交审批：adjustNo={}, type={}, itemId={}, applicantId={}",
            adjust.getAdjustNo(), adjustType.getCode(), item.getId(), operatorId);
        return adjust;
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
            case DISCOUNT -> executeDiscount(adjust);
            case DIFF -> executeDiff(adjust);
            case VOID -> executeVoid(adjust);
            default -> throw new ServiceException("非法调整类型：" + type);
        }
        log.info("[结佣-调整] 调整单已执行：adjustNo={}, type={}", adjust.getAdjustNo(), type.getCode());
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
    public PageResult<CommissionAdjust> listAdjusts(AdjustQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<CommissionAdjust> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.isNotBlank(query.getPeriod()), CommissionAdjust::getPeriod, query.getPeriod())
            .eq(query.getApplicationId() != null, CommissionAdjust::getApplicationId, query.getApplicationId())
            .eq(StringUtils.isNotBlank(query.getAdjustType()), CommissionAdjust::getAdjustType,
                AdjustType.fromCode(query.getAdjustType()))
            .eq(StringUtils.isNotBlank(query.getStatus()), CommissionAdjust::getStatus,
                AdjustStatus.fromCode(query.getStatus()))
            .orderByDesc(CommissionAdjust::getCreateTime);
        var page = adjustMapper.selectPage(pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
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
        return adjust;
    }

    /**
     * 取消调整单：仅 SUBMITTED 可取消。
     *
     * @param adjustId   调整单 ID
     * @param operatorId 操作人 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long adjustId, Long operatorId) {
        CommissionAdjust adjust = getAdjust(adjustId);
        if (adjust.getStatus() != AdjustStatus.SUBMITTED) {
            throw new ServiceException("仅已提交状态可取消（当前：" + adjust.getStatus().getDesc() + "）");
        }
        adjust.setStatus(AdjustStatus.CANCELLED);
        adjustMapper.updateById(adjust);
        log.info("[结佣-调整] 调整单已取消：adjustNo={}, operatorId={}", adjust.getAdjustNo(), operatorId);
    }

    // ==================== EXECUTED 同事务动作 ====================

    /**
     * DISCOUNT：旧明细 REVERSED + 新明细（amount=折后值，performance_fact_id 沿用原值）。
     * <p>
     * 新明细状态沿用源明细：PENDING → PENDING（随后续审批流转）；
     * APPROVED → APPROVED（同 approved_month，保证 payroll 按锁定口径继续取到折后金额，
     * 避免"旧行 REVERSED + 新行 PENDING"导致已确认金额从工资口径消失）。
     * 调整单审批通过即视为该折后金额的审批确认。
     */
    private void executeDiscount(CommissionAdjust adjust) {
        CommissionItem oldItem = itemMapper.selectById(adjust.getItemId());
        requireItem(oldItem, adjust);

        // 旧明细冲销（REVERSED 终态，永久保留）
        oldItem.setStatus(ItemStatus.REVERSED);
        oldItem.setReversedReason(ReversedReason.MANUAL_ADJUST);
        oldItem.setAdjustId(adjust.getId());
        int rows = itemMapper.updateById(oldItem);
        if (rows == 0) {
            throw new ServiceException("明细并发冲突，执行失败，请重试：itemId=" + oldItem.getId());
        }

        // 新明细：沿用同一 performance_fact_id（uk_citem_fact_active 排除 REVERSED → 不撞键）
        CommissionItem newItem = new CommissionItem();
        newItem.setApplicationId(oldItem.getApplicationId());
        newItem.setPerformanceFactId(oldItem.getPerformanceFactId());
        newItem.setPeriod(oldItem.getPeriod());
        newItem.setApprovedMonth(oldItem.getApprovedMonth());
        newItem.setEmployeeId(oldItem.getEmployeeId());
        newItem.setDeptId(oldItem.getDeptId());
        newItem.setBizType(oldItem.getBizType());
        newItem.setRoleType(oldItem.getRoleType());
        newItem.setFeeItem(oldItem.getFeeItem());
        newItem.setAmount(adjust.getNewAmount());
        newItem.setStatus(oldItem.getStatus());
        newItem.setOriginReversed(false);
        newItem.setAdjustId(adjust.getId());
        itemMapper.insert(newItem);

        applicationService.recalcAggregates(adjust.getApplicationId(), null);
        log.info("[结佣-调整-DISCOUNT] 折后金额落地：oldItemId={}, newItemId={}, amount={}",
            oldItem.getId(), newItem.getId(), adjust.getNewAmount());
    }

    /**
     * DIFF：新增差额明细（performance_fact_id = NULL，period = target_period）。
     * <p>
     * 差额明细为独立凭证：调整单审批通过即确认，status = APPROVED、approved_month = target_period
     * （补发到哪个月就计入哪个月工资）。
     */
    private void executeDiff(CommissionAdjust adjust) {
        CommissionItem sourceItem = itemMapper.selectById(adjust.getItemId());
        requireItem(sourceItem, adjust);

        CommissionItem diffItem = new CommissionItem();
        diffItem.setApplicationId(adjust.getApplicationId());
        diffItem.setPerformanceFactId(null);
        diffItem.setPeriod(adjust.getTargetPeriod());
        diffItem.setApprovedMonth(adjust.getTargetPeriod());
        diffItem.setEmployeeId(sourceItem.getEmployeeId());
        diffItem.setDeptId(sourceItem.getDeptId());
        diffItem.setBizType(sourceItem.getBizType());
        diffItem.setRoleType(sourceItem.getRoleType());
        diffItem.setFeeItem(sourceItem.getFeeItem());
        diffItem.setAmount(adjust.getDiffAmount());
        diffItem.setStatus(ItemStatus.APPROVED);
        diffItem.setOriginReversed(false);
        diffItem.setAdjustId(adjust.getId());
        itemMapper.insert(diffItem);

        applicationService.recalcAggregates(adjust.getApplicationId(), null);
        log.info("[结佣-调整-DIFF] 差额明细已生成：newItemId={}, targetPeriod={}, amount={}",
            diffItem.getId(), adjust.getTargetPeriod(), adjust.getDiffAmount());
    }

    /**
     * VOID：旧明细 REVERSED + 聚合重算。
     */
    private void executeVoid(CommissionAdjust adjust) {
        CommissionItem oldItem = itemMapper.selectById(adjust.getItemId());
        requireItem(oldItem, adjust);

        oldItem.setStatus(ItemStatus.REVERSED);
        oldItem.setReversedReason(ReversedReason.MANUAL_ADJUST);
        oldItem.setAdjustId(adjust.getId());
        int rows = itemMapper.updateById(oldItem);
        if (rows == 0) {
            throw new ServiceException("明细并发冲突，执行失败，请重试：itemId=" + oldItem.getId());
        }

        applicationService.recalcAggregates(adjust.getApplicationId(), null);
        log.info("[结佣-调整-VOID] 明细已作废：itemId={}", oldItem.getId());
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

    /**
     * 变更前后值快照 JSON（审计）。
     */
    private String buildPayload(CommissionItem item, AdjustCreateDTO dto) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("before", Map.of(
            "itemId", item.getId(),
            "amount", item.getAmount(),
            "status", item.getStatus().getCode(),
            "period", item.getPeriod()));
        Map<String, Object> after = new LinkedHashMap<>();
        if (dto.getNewAmount() != null) {
            after.put("amount", dto.getNewAmount());
        }
        if (dto.getDiffAmount() != null) {
            after.put("diffAmount", dto.getDiffAmount());
        }
        if (StringUtils.isNotBlank(dto.getTargetPeriod())) {
            after.put("targetPeriod", dto.getTargetPeriod());
        }
        payload.put("after", after);
        payload.put("reason", dto.getReason());
        return objectMapper.writeValueAsString(payload);
    }
}
