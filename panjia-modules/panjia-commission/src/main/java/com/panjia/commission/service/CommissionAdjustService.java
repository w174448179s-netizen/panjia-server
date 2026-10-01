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
import com.panjia.commission.domain.bo.CommissionAdjustPayload;
import com.panjia.commission.domain.vo.CommissionItemDetailVo;
import com.panjia.commission.mapper.CommissionAdjustMapper;
import com.panjia.commission.mapper.CommissionApplicationMapper;
import com.panjia.commission.mapper.CommissionItemMapper;
import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ApprovalStartCmd;
import com.panjia.contracts.port.CommissionPerformanceQueryPort;
import com.panjia.contracts.port.ConversionFactorPort;
import com.panjia.contracts.port.EmployeeMainDataQueryPort;
import com.panjia.contracts.port.PeriodCloseQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    /** 申请单 mapper：合同级调整按订单号/合同号双键跨月查找事实（明细绑定跨月新签事实） */
    private final CommissionApplicationMapper applicationMapper;
    /** 折算比例唯一来源：契约层端口（规则表由薪酬域持有，本域不直连） */
    private final ConversionFactorPort conversionFactorPort;
    private final CommissionApplicationService applicationService;
    private final PeriodCloseQueryPort periodCloseQueryPort;
    private final ObjectMapper objectMapper;
    private final ApprovalPort approvalPort;
    /** 业绩事实跨域端口：结佣调整直接操作 PERF_REAL + PERF_EXPECT 事实 */
    private final CommissionPerformanceQueryPort performanceQueryPort;
    /** 员工主数据端口：增加角色人时回填新人工号/部门 */
    private final EmployeeMainDataQueryPort employeeMainDataQueryPort;

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
        } else if (adjustType == AdjustType.ADD_MEMBER) {
            if (detailScope) {
                throw new ServiceException("增加角色人仅支持合同级调整");
            }
            if (dto.getNewMember() == null || dto.getNewMember().getEmployeeId() == null) {
                throw new ServiceException("增加角色人必须指定新员工");
            }
            if (dto.getNewMember().getAmount() == null
                || dto.getNewMember().getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ServiceException("新角色人业绩金额必须大于 0");
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
            // 结佣金额口径=新签（PERF_EXPECT），调整基准取新签合计；实收仅为门控不参与金额
            originalAmount = sumFacts(application.getPeriod(), application.getContractNo(), FACT_TYPE_EXPECT);
        }

        // 合同级可编辑表格模式（镜像新签调整）：detailTargets/newMember 快照入 payload_json，
        // 执行端按指定值逐行精确落库；ADD_MEMBER 的 newAmount 存新人金额（targetAmount 语义对新人类型不适用）
        String payloadJson = null;
        BigDecimal targetAmount = dto.getTargetAmount();
        BigDecimal deltaAmount;
        if (!detailScope && adjustType == AdjustType.AMOUNT
            && dto.getDetailTargets() != null && !dto.getDetailTargets().isEmpty()) {
            payloadJson = prepareContractTargets(application, dto);
            deltaAmount = targetAmount == null ? null
                : targetAmount.subtract(originalAmount == null ? BigDecimal.ZERO : originalAmount);
        } else if (!detailScope && adjustType == AdjustType.ADD_MEMBER) {
            payloadJson = prepareAddMemberPayload(application, dto);
            // newAmount 存新人金额；diffAmount 存合同总额变化（默认总额不变 = 0，混合金额调整时 = afterTotal − 原合计）
            targetAmount = dto.getNewMember().getAmount();
            CommissionAdjustPayload payload = JsonUtils.parseObject(payloadJson, CommissionAdjustPayload.class);
            BigDecimal afterTotal = payload != null && payload.getAfterTotal() != null
                ? payload.getAfterTotal() : originalAmount;
            deltaAmount = afterTotal.subtract(originalAmount == null ? BigDecimal.ZERO : originalAmount);
        } else {
            deltaAmount = targetAmount == null ? null
                : targetAmount.subtract(originalAmount == null ? BigDecimal.ZERO : originalAmount);
        }

        CommissionAdjust adjust = new CommissionAdjust();
        adjust.setAdjustNo("CADJ" + LocalDateTime.now().format(ADJUST_NO_FORMATTER));
        adjust.setApplicationId(application.getId());
        adjust.setItemId(detailScope ? item.getId() : null);
        adjust.setPeriod(application.getPeriod());
        adjust.setAdjustType(adjustType);
        adjust.setNewAmount(targetAmount);            // 复用 new_amount 列：调整后金额（ADD_MEMBER=新人金额）
        adjust.setDiffAmount(deltaAmount);            // 复用 diff_amount 列：调整差额
        adjust.setOriginalAmount(originalAmount);
        adjust.setContractNo(application.getContractNo());
        adjust.setFactType(FACT_TYPE_EXPECT);
        adjust.setAdjustScope(dto.getAdjustScope());
        adjust.setFactId(factId);
        adjust.setTargetDeptId(dto.getTargetDeptId());
        adjust.setPayloadJson(payloadJson);
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

    /**
     * 合同级金额调整·指定值模式：校验并快照「调整后金额/角色占比」入 payload_json。
     */
    private String prepareContractTargets(CommissionApplication app, CommissionAdjustCreateBo dto) {
        List<CommissionItem> items = listActiveItems(app.getId());
        Map<Long, CommissionItem> itemById = items.stream()
            .collect(Collectors.toMap(CommissionItem::getId, i -> i));

        List<CommissionAdjustPayload.DetailTarget> validTargets = new ArrayList<>();
        Set<Long> coveredIds = new HashSet<>();
        BigDecimal coveredSum = BigDecimal.ZERO;
        for (CommissionAdjustCreateBo.DetailTarget t : dto.getDetailTargets()) {
            if (t == null || t.getItemId() == null || t.getTargetAmount() == null) continue;
            if (itemById.get(t.getItemId()) == null) {
                throw new ServiceException("指定调整行不在该合同结佣明细中：itemId=" + t.getItemId());
            }
            if (t.getShareRatio() != null && t.getShareRatio().signum() <= 0) {
                throw new ServiceException("角色占比必须大于 0：itemId=" + t.getItemId());
            }
            validTargets.add(toPayloadTarget(t));
            coveredIds.add(t.getItemId());
            coveredSum = coveredSum.add(t.getTargetAmount());
        }
        BigDecimal uncoveredSum = items.stream()
            .filter(i -> !coveredIds.contains(i.getId()))
            .map(i -> i.getAmount() == null ? BigDecimal.ZERO : i.getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (dto.getTargetAmount() == null
            || round2(coveredSum.add(uncoveredSum)).compareTo(round2(dto.getTargetAmount())) != 0) {
            throw new ServiceException("明细调整后金额合计(" + round2(coveredSum.add(uncoveredSum))
                + ")与目标金额(" + dto.getTargetAmount() + ")不一致，请检查录入或刷新数据后重试");
        }

        CommissionAdjustPayload payload = new CommissionAdjustPayload();
        payload.setDetailTargets(validTargets);
        payload.setContractTotal(round2(items.stream()
            .map(i -> i.getAmount() == null ? BigDecimal.ZERO : i.getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add)));
        payload.setAfterTotal(round2(dto.getTargetAmount()));
        return JsonUtils.toJsonString(payload);
    }

    /**
     * 增加角色人·payload 快照构建：校验新人 + 既有行分摊预演。
     * 允许同时混合金额调整（detailTargets 非空时按指定值模式，否则按等比让出）。
     */
    private String prepareAddMemberPayload(CommissionApplication app, CommissionAdjustCreateBo dto) {
        List<CommissionItem> items = listActiveItems(app.getId());
        BigDecimal total = items.stream()
            .map(i -> i.getAmount() == null ? BigDecimal.ZERO : i.getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        CommissionAdjustCreateBo.NewMember nm = dto.getNewMember();
        if (nm.getAmount().compareTo(total) > 0) {
            throw new ServiceException("新角色人业绩金额超过合同当前结佣合计，会导致负数");
        }
        Long newEmpId = nm.getEmployeeId();
        for (CommissionItem it : items) {
            if (newEmpId.equals(it.getEmployeeId())) {
                throw new ServiceException("该员工已在此合同结佣明细中，不能重复增加");
            }
        }

        EmployeeMainDataDTO emp = employeeMainDataQueryPort.getByEmployeeId(newEmpId);
        BigDecimal afterTotal = total;
        CommissionAdjustPayload payload = new CommissionAdjustPayload();
        payload.setNewMember(toPayloadNewMember(nm, emp));
        payload.setContractTotal(round2(total));

        if (dto.getDetailTargets() != null && !dto.getDetailTargets().isEmpty()) {
            // 指定值模式：既有行按用户录入目标精确调整，不再等比让出
            List<CommissionAdjustPayload.DetailTarget> validTargets = new ArrayList<>();
            Map<Long, CommissionItem> itemById = items.stream()
                .collect(Collectors.toMap(CommissionItem::getId, i -> i));
            BigDecimal coveredSum = BigDecimal.ZERO;
            Set<Long> coveredIds = new HashSet<>();
            for (CommissionAdjustCreateBo.DetailTarget t : dto.getDetailTargets()) {
                if (t == null || t.getItemId() == null || t.getTargetAmount() == null) continue;
                if (itemById.get(t.getItemId()) == null) {
                    throw new ServiceException("指定调整行不在该合同结佣明细中：itemId=" + t.getItemId());
                }
                validTargets.add(toPayloadTarget(t));
                coveredIds.add(t.getItemId());
                coveredSum = coveredSum.add(t.getTargetAmount());
            }
            BigDecimal uncoveredSum = items.stream()
                .filter(i -> !coveredIds.contains(i.getId()))
                .map(i -> i.getAmount() == null ? BigDecimal.ZERO : i.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            afterTotal = round2(coveredSum.add(uncoveredSum).add(nm.getAmount()));
            payload.setDetailTargets(validTargets);
        }
        payload.setAfterTotal(round2(afterTotal));
        return JsonUtils.toJsonString(payload);
    }

    private CommissionAdjustPayload.DetailTarget toPayloadTarget(CommissionAdjustCreateBo.DetailTarget t) {
        CommissionAdjustPayload.DetailTarget p = new CommissionAdjustPayload.DetailTarget();
        p.setItemId(t.getItemId());
        p.setTargetAmount(round2(t.getTargetAmount()));
        if (t.getShareRatio() != null) p.setShareRatio(round6(t.getShareRatio()));
        return p;
    }

    private CommissionAdjustPayload.NewMember toPayloadNewMember(
        CommissionAdjustCreateBo.NewMember nm, EmployeeMainDataDTO emp) {
        CommissionAdjustPayload.NewMember p = new CommissionAdjustPayload.NewMember();
        p.setEmployeeId(nm.getEmployeeId());
        p.setEmployeeCode(emp != null ? emp.getEmployeeCode() : null);
        p.setEmployeeName(emp != null ? emp.getEmployeeName() : null);
        p.setDeptId(nm.getDeptId() != null ? nm.getDeptId() : (emp != null ? emp.getDeptId() : null));
        p.setRoleType(StringUtils.isBlank(nm.getRoleType()) ? "合作人" : nm.getRoleType().trim());
        p.setAmount(round2(nm.getAmount()));
        if (nm.getShareRatio() != null) p.setShareRatio(round6(nm.getShareRatio()));
        return p;
    }

    private static BigDecimal round2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }
    private static BigDecimal round6(BigDecimal v) {
        return v == null ? null : v.setScale(6, RoundingMode.HALF_UP);
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
            case ADD_MEMBER -> executeAddMemberAdjust(adjust, approverId);
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
        fillAdjustDetail(adjust);
        return adjust;
    }

    /**
     * 填充调整单详情展示字段（合同信息 + 受影响明细预演，不入库）。
     * <p>
     * 对齐新签调整详情（AdjustDetailPanel）三段式：基础信息 / 合同信息 / 受影响明细。
     * 受影响明细行取申请单下全部结佣明细，按调整类型/范围预演每行 变动额/调整后金额：
     * <ul>
     *   <li>AMOUNT 明细级：仅目标行变动（delta=diffAmount，after=newAmount）；</li>
     *   <li>AMOUNT 合同级：按金额占比分摊，尾差归最后一行使 Σ调整后 = newAmount；</li>
     *   <li>VOID：冲销行 after=0；TRANSFER/旧类型（DISCOUNT/DIFF）：金额不变仅标记。</li>
     * </ul>
     * 注意：EXECUTED 单回看时明细已是调整后金额，预演以单据快照（original/diff/newAmount）为准。
     */
    private void fillAdjustDetail(CommissionAdjust adjust) {
        CommissionApplication app = adjust.getApplicationId() == null
            ? null : applicationMapper.selectById(adjust.getApplicationId());
        if (app != null) {
            adjust.setOrderNo(app.getOrderNo());
            adjust.setPropertyAddress(app.getPropertyAddress());
        }
        if (adjust.getAdjustType() == AdjustType.TRANSFER && adjust.getTargetDeptId() != null) {
            adjust.setTargetDeptName(adjustMapper.selectDeptName(adjust.getTargetDeptId()));
        }
        if (adjust.getApplicationId() == null) {
            adjust.setDetails(List.of());
            adjust.setDetailCount(0);
            return;
        }
        List<CommissionItemDetailVo> rows = itemMapper.selectItemDetails(adjust.getApplicationId());
        boolean detailScope = SCOPE_DETAIL.equals(adjust.getAdjustScope());
        AdjustType type = adjust.getAdjustType();
        BigDecimal delta = adjust.getDiffAmount() != null ? adjust.getDiffAmount()
            : (adjust.getNewAmount() != null && adjust.getOriginalAmount() != null
                ? adjust.getNewAmount().subtract(adjust.getOriginalAmount()) : null);

        // 指定值模式（payload.detailTargets 非空）：按快照逐行精确预演，不再等比分摊；
        // ADD_MEMBER 追加新角色人虚拟行（amount=0、afterAmount=新人金额、target=true）。
        CommissionAdjustPayload payload = (!detailScope
            && (type == AdjustType.AMOUNT || type == AdjustType.ADD_MEMBER))
            ? parsePayload(adjust.getPayloadJson()) : null;
        Map<Long, CommissionAdjustPayload.DetailTarget> targetByItemId = new HashMap<>();
        if (payload != null && payload.getDetailTargets() != null) {
            for (CommissionAdjustPayload.DetailTarget t : payload.getDetailTargets()) {
                if (t != null && t.getItemId() != null) {
                    targetByItemId.put(t.getItemId(), t);
                }
            }
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (CommissionItemDetailVo row : rows) {
            if (row.getAmount() != null) {
                sum = sum.add(row.getAmount());
            }
        }
        int lastIdx = rows.size() - 1;
        for (int i = 0; i < rows.size(); i++) {
            CommissionItemDetailVo row = rows.get(i);
            boolean target = detailScope
                ? row.getItemId() != null && row.getItemId().equals(adjust.getItemId())
                : true;
            row.setTarget(target);
            BigDecimal amount = row.getAmount() == null ? BigDecimal.ZERO : row.getAmount();
            if (type == AdjustType.VOID) {
                row.setDeltaAmount(amount.negate());
                row.setAfterAmount(BigDecimal.ZERO);
            } else if (!detailScope && !targetByItemId.isEmpty()) {
                // 合同级指定值模式（AMOUNT / ADD_MEMBER）：指定行 after=快照目标，未指定行不变
                CommissionAdjustPayload.DetailTarget t = targetByItemId.get(row.getItemId());
                if (t != null && t.getTargetAmount() != null) {
                    if (adjust.getStatus() == AdjustStatus.EXECUTED) {
                        // 审批后回看：行金额已是调整后值，取事实链最早值作为「调整前」展示真实变化
                        BigDecimal before = row.getOriginalAmount() != null ? row.getOriginalAmount() : amount;
                        row.setAfterAmount(amount);
                        row.setDeltaAmount(amount.subtract(before));
                    } else {
                        row.setAfterAmount(t.getTargetAmount());
                        row.setDeltaAmount(t.getTargetAmount().subtract(amount));
                    }
                } else if (adjust.getStatus() == AdjustStatus.EXECUTED && type == AdjustType.ADD_MEMBER
                    && row.getOriginalAmount() == null) {
                    // ADD_MEMBER 审批后：新人行（本单新建明细，无事实链原值）展示 0 → X
                    row.setDeltaAmount(amount);
                    row.setAfterAmount(amount);
                } else {
                    row.setDeltaAmount(BigDecimal.ZERO);
                    row.setAfterAmount(amount);
                }
            } else if (type == AdjustType.ADD_MEMBER) {
                // ADD_MEMBER 无指定值快照（旧单兼容）：既有行金额不变，仅末尾追加新人虚拟行
                row.setDeltaAmount(BigDecimal.ZERO);
                row.setAfterAmount(amount);
            } else if (type == AdjustType.AMOUNT && delta != null) {
                if (detailScope) {
                    if (target) {
                        row.setDeltaAmount(delta);
                        row.setAfterAmount(amount.add(delta));
                    } else {
                        row.setDeltaAmount(BigDecimal.ZERO);
                        row.setAfterAmount(amount);
                    }
                } else {
                    // 合同级按金额占比分摊；尾差归最后一行，保证 Σ调整后 = newAmount
                    BigDecimal after = sum.signum() == 0
                        ? amount.add(delta.divide(BigDecimal.valueOf(rows.size()), 2, java.math.RoundingMode.HALF_UP))
                        : amount.add(delta.multiply(amount).divide(sum, 2, java.math.RoundingMode.HALF_UP));
                    if (i == lastIdx && adjust.getNewAmount() != null) {
                        BigDecimal prevSum = BigDecimal.ZERO;
                        for (int j = 0; j < i; j++) {
                            prevSum = prevSum.add(rows.get(j).getAfterAmount() == null
                                ? BigDecimal.ZERO : rows.get(j).getAfterAmount());
                        }
                        after = adjust.getNewAmount().subtract(prevSum);
                    }
                    row.setAfterAmount(after);
                    row.setDeltaAmount(after.subtract(amount));
                }
            } else {
                // TRANSFER / 旧类型（DISCOUNT/DIFF）：金额不变
                row.setDeltaAmount(BigDecimal.ZERO);
                row.setAfterAmount(amount);
            }
            if (detailScope && target) {
                adjust.setEmployeeName(row.getEmployeeName());
                adjust.setEmployeeCode(row.getEmployeeCode());
            }
        }
        // ADD_MEMBER：末尾追加新角色人虚拟行（未执行时明细中尚无该行）
        if (!detailScope && type == AdjustType.ADD_MEMBER && payload != null && payload.getNewMember() != null
            && adjust.getStatus() != AdjustStatus.EXECUTED) {
            CommissionAdjustPayload.NewMember nm = payload.getNewMember();
            CommissionItemDetailVo virtualRow = new CommissionItemDetailVo();
            virtualRow.setEmployeeId(nm.getEmployeeId());
            virtualRow.setEmployeeCode(nm.getEmployeeCode());
            virtualRow.setEmployeeName(nm.getEmployeeName());
            virtualRow.setRoleType(nm.getRoleType());
            virtualRow.setShareRatio(nm.getShareRatio());
            virtualRow.setAmount(BigDecimal.ZERO);
            virtualRow.setDeltaAmount(nm.getAmount());
            virtualRow.setAfterAmount(nm.getAmount());
            virtualRow.setTarget(true);
            rows.add(virtualRow);
            adjust.setEmployeeName(nm.getEmployeeName());
            adjust.setEmployeeCode(nm.getEmployeeCode());
        }
        adjust.setDetails(rows);
        adjust.setDetailCount(rows.size());
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
            if (r.getOriginalAmount() != null) {
                r.setConvertedOriginalAmount(conversionFactorPort.convert(r.getOriginalAmount(), factor));
            }
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
     * 金额调整（AMOUNT）：双口径同步调整，2026-09-27 定稿后明细绑定期望事实（新签口径）。
     * <ul>
     *   <li>合同级：PERF_REAL 分摊调整到 targetAmount；PERF_EXPECT 当期事实承担差额
     *       （差额基准 = 当前明细Σ，跨月期望事实不动 → 调整后 Σ明细 = targetAmount 精确成立）；
     *       回写明细 amount / performance_fact_id（expectMapping 优先、realMapping 兜底）；</li>
     *   <li>明细级：明细绑定 PERF_EXPECT（新口径单）→ 直接 supersede 绑定事实；
     *       绑定 PERF_REAL（历史单）→ 实收单条 supersede + 同员工应收同步同一差额。</li>
     * </ul>
     */
    private void executeAmountAdjust(CommissionAdjust adjust, Long approverId) {
        BigDecimal targetAmount = adjust.getNewAmount();
        BigDecimal delta = adjust.getDiffAmount();
        if (SCOPE_CONTRACT.equals(adjust.getAdjustScope())) {
            CommissionAdjustPayload payload = parsePayload(adjust.getPayloadJson());
            if (payload != null && payload.getDetailTargets() != null && !payload.getDetailTargets().isEmpty()) {
                executeContractAmountByTargets(adjust, payload, approverId);
                return;
            }
            // 结佣金额口径=新签（PERF_EXPECT）：合同级调整只 supersede 新签事实，不动实收（实收仅门控）。
            // 以明细合计为基准，差额由当期新签事实按金额占比分摊（跨月新签事实不动）。
            BigDecimal currentDetailSum = listActiveItems(adjust.getApplicationId()).stream()
                .map(i -> i.getAmount() == null ? BigDecimal.ZERO : i.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal currentExpect = sumFacts(adjust.getPeriod(), adjust.getContractNo(), FACT_TYPE_EXPECT);
            BigDecimal targetExpect = currentExpect.add(targetAmount.subtract(currentDetailSum));
            Map<Long, Long> expectMapping = performanceQueryPort.adjustContractFactsAmount(
                adjust.getPeriod(), adjust.getContractNo(), FACT_TYPE_EXPECT, targetExpect, approverId, adjust.getId());
            // 回写 CommissionItem：performance_fact_id 指向新事实，amount 从新事实回查
            List<CommissionItem> items = listActiveItems(adjust.getApplicationId());
            for (CommissionItem item : items) {
                Long newFactId = expectMapping.get(item.getPerformanceFactId());
                if (newFactId != null) {
                    item.setPerformanceFactId(newFactId);
                    item.setAdjustId(adjust.getId());
                }
            }
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
            log.info("[结佣-调整-AMOUNT-合同级] 完成（仅调新签）：adjustId={}, targetExpect={}, expectFacts={}",
                adjust.getId(), targetExpect, expectMapping.size());
        } else {
            // 明细级
            CommissionItem item = itemMapper.selectById(adjust.getItemId());
            requireItem(item, adjust);
            if (boundFactIsExpect(item)) {
                // 新口径（2026-09-27）：明细绑定 PERF_EXPECT 事实，直接 supersede 为调整后金额
                Long newFactId = performanceQueryPort.adjustFactAmount(
                    item.getPerformanceFactId(), targetAmount, approverId, adjust.getId());
                item.setAmount(targetAmount);
                if (newFactId != null) {
                    item.setPerformanceFactId(newFactId);
                }
                item.setAdjustId(adjust.getId());
                itemMapper.updateById(item);
            } else {
                // 历史单（明细绑 PERF_REAL）：实收单条 supersede + 同员工应收同步同一差额
                Long newRealFactId = performanceQueryPort.adjustFactAmount(
                    item.getPerformanceFactId(), targetAmount, approverId, adjust.getId());
                PerformanceFactSummaryDTO expectFact = findExpectByEmployee(
                    adjust.getPeriod(), adjust.getContractNo(), item.getEmployeeId());
                if (expectFact != null) {
                    BigDecimal expectTarget = expectFact.getAmount().add(delta == null ? BigDecimal.ZERO : delta);
                    performanceQueryPort.adjustFactAmount(
                        expectFact.getFactId(), expectTarget, approverId, adjust.getId());
                }
                item.setAmount(targetAmount);
                if (newRealFactId != null) {
                    item.setPerformanceFactId(newRealFactId);
                }
                item.setAdjustId(adjust.getId());
                itemMapper.updateById(item);
            }
            applicationService.recalcAggregates(adjust.getApplicationId(), null);
            log.info("[结佣-调整-AMOUNT-明细级] 完成：adjustId={}, itemId={}, delta={}",
                adjust.getId(), item.getId(), delta);
        }
    }

    /**
     * 合同级金额调整·指定值模式执行：既有行按 payload.detailTargets 精确 supersede（金额 + 可选角色占比）。
     * <p>
     * 完整性校验：Σ指定行目标 + 未指定行当前金额 = 单据目标金额；执行时合计被其他调整单抢先变化则拒绝执行。
     */
    private void executeContractAmountByTargets(CommissionAdjust adjust, CommissionAdjustPayload payload, Long approverId) {
        List<CommissionItem> items = listActiveItems(adjust.getApplicationId());
        Map<Long, CommissionItem> itemById = items.stream()
            .collect(Collectors.toMap(CommissionItem::getId, i -> i));

        // 完整性校验：指定行必须全部存在，且目标合计 + 未指定行当前金额 = 单据目标金额
        BigDecimal coveredSum = BigDecimal.ZERO;
        Set<Long> coveredIds = new HashSet<>();
        for (CommissionAdjustPayload.DetailTarget t : payload.getDetailTargets()) {
            if (t == null || t.getItemId() == null || t.getTargetAmount() == null) continue;
            if (itemById.get(t.getItemId()) == null) {
                throw new ServiceException("执行失败：指定调整行在执行时已不存在（可能被其他调整单抢先执行），"
                    + "adjustId=" + adjust.getId() + ", itemId=" + t.getItemId());
            }
            coveredIds.add(t.getItemId());
            coveredSum = coveredSum.add(t.getTargetAmount());
        }
        BigDecimal uncoveredSum = items.stream()
            .filter(i -> !coveredIds.contains(i.getId()))
            .map(i -> i.getAmount() == null ? BigDecimal.ZERO : i.getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (round2(coveredSum.add(uncoveredSum)).compareTo(round2(adjust.getNewAmount())) != 0) {
            throw new ServiceException("执行失败：执行时合同结佣合计已变化（Σ指定值" + round2(coveredSum)
                + " + 未指定行" + round2(uncoveredSum) + " ≠ 目标金额" + round2(adjust.getNewAmount())
                + "），为避免金额错乱终止执行：adjustId=" + adjust.getId());
        }

        int affected = applyDetailTargets(adjust, itemById, payload.getDetailTargets(), approverId);
        applicationService.recalcAggregates(adjust.getApplicationId(), null);
        log.info("[结佣-调整-AMOUNT-合同级-指定值] 完成：adjustId={}, 明细数={}, 实际调整条数={}, 目标金额={}",
            adjust.getId(), items.size(), affected, round2(adjust.getNewAmount()));
    }

    /**
     * 逐行执行指定值调整：金额或占比有变化才 supersede 绑定的新签事实，回写明细 amount/performance_fact_id。
     *
     * @return 实际调整条数
     */
    private int applyDetailTargets(CommissionAdjust adjust, Map<Long, CommissionItem> itemById,
                                   List<CommissionAdjustPayload.DetailTarget> targets, Long approverId) {
        int affected = 0;
        for (CommissionAdjustPayload.DetailTarget t : targets) {
            if (t == null || t.getItemId() == null || t.getTargetAmount() == null) continue;
            CommissionItem item = itemById.get(t.getItemId());
            BigDecimal current = item.getAmount() == null ? BigDecimal.ZERO : item.getAmount();
            BigDecimal target = round2(t.getTargetAmount());
            boolean amountChanged = target.compareTo(current) != 0;
            if (!amountChanged && t.getShareRatio() == null) {
                continue;
            }
            Long newFactId = performanceQueryPort.adjustFactAmount(
                item.getPerformanceFactId(), target, t.getShareRatio(), approverId, adjust.getId());
            item.setAmount(target);
            if (newFactId != null) {
                item.setPerformanceFactId(newFactId);
            }
            item.setAdjustId(adjust.getId());
            itemMapper.updateById(item);
            affected++;
        }
        return affected;
    }

    /**
     * 增加角色人（ADD_MEMBER，合同级）：既有行按 payload.detailTargets 精确 supersede，再插入新事实 + 新明细。
     * <p>
     * 默认不变量：执行后合同结佣合计 = 执行前（新事实 +X，既有事实合计 -X）；
     * 混合金额调整（payload.afterTotal ≠ 执行前合计）时以快照 afterTotal 为准做完整性校验。
     */
    private void executeAddMemberAdjust(CommissionAdjust adjust, Long approverId) {
        CommissionAdjustPayload payload = parsePayload(adjust.getPayloadJson());
        if (payload == null || payload.getNewMember() == null) {
            throw new ServiceException("增加角色人调整缺少新角色人快照：adjustId=" + adjust.getId());
        }
        CommissionAdjustPayload.NewMember nm = payload.getNewMember();
        BigDecimal newAmount = nm.getAmount();
        if (newAmount == null || newAmount.signum() <= 0) {
            throw new ServiceException("增加角色人调整缺少新角色人业绩金额：adjustId=" + adjust.getId());
        }

        List<CommissionItem> items = listActiveItems(adjust.getApplicationId());
        if (items.isEmpty()) {
            throw new ServiceException("申请单下未找到有效结佣明细：applicationId=" + adjust.getApplicationId());
        }
        Map<Long, CommissionItem> itemById = items.stream()
            .collect(Collectors.toMap(CommissionItem::getId, i -> i));
        for (CommissionItem it : items) {
            if (nm.getEmployeeId().equals(it.getEmployeeId())) {
                throw new ServiceException("该员工已在此合同结佣明细中，不能重复增加：adjustId=" + adjust.getId()
                    + ", employeeId=" + nm.getEmployeeId());
            }
        }

        // 既有行指定值执行 + 完整性校验：Σ指定行目标 + 未指定行现值 + 新人金额 = 发起时快照 afterTotal
        if (payload.getDetailTargets() != null && !payload.getDetailTargets().isEmpty()) {
            BigDecimal coveredSum = BigDecimal.ZERO;
            Set<Long> coveredIds = new HashSet<>();
            for (CommissionAdjustPayload.DetailTarget t : payload.getDetailTargets()) {
                if (t == null || t.getItemId() == null || t.getTargetAmount() == null) continue;
                if (itemById.get(t.getItemId()) == null) {
                    throw new ServiceException("执行失败：指定调整行在执行时已不存在（可能被其他调整单抢先执行），"
                        + "adjustId=" + adjust.getId() + ", itemId=" + t.getItemId());
                }
                coveredIds.add(t.getItemId());
                coveredSum = coveredSum.add(t.getTargetAmount());
            }
            BigDecimal uncoveredSum = items.stream()
                .filter(i -> !coveredIds.contains(i.getId()))
                .map(i -> i.getAmount() == null ? BigDecimal.ZERO : i.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal actualAfterTotal = round2(coveredSum.add(uncoveredSum).add(newAmount));
            if (payload.getAfterTotal() != null && actualAfterTotal.compareTo(round2(payload.getAfterTotal())) != 0) {
                throw new ServiceException("执行失败：执行时合同结佣合计已变化（既有行指定值" + round2(coveredSum)
                    + " + 未指定行" + round2(uncoveredSum) + " + 新人" + round2(newAmount)
                    + " ≠ 发起时调整后总额" + round2(payload.getAfterTotal())
                    + "），为避免金额错乱终止执行：adjustId=" + adjust.getId());
            }
            applyDetailTargets(adjust, itemById, payload.getDetailTargets(), approverId);
        }

        // 新事实 + 新结佣明细（模板=申请单首条明细绑定的事实，期间/合同/业务类型等基础字段沿用）
        CommissionItem templateItem = items.get(0);
        Long newFactId = performanceQueryPort.createMemberFact(
            templateItem.getPerformanceFactId(), nm.getEmployeeId(), nm.getEmployeeCode(), nm.getDeptId(),
            nm.getRoleType(), newAmount, nm.getShareRatio(), approverId, adjust.getId());

        CommissionItem newItem = new CommissionItem();
        newItem.setApplicationId(adjust.getApplicationId());
        newItem.setPerformanceFactId(newFactId);
        newItem.setContractNo(templateItem.getContractNo());
        newItem.setPeriod(templateItem.getPeriod());
        newItem.setApprovedMonth(templateItem.getApprovedMonth());
        newItem.setEmployeeId(nm.getEmployeeId());
        newItem.setDeptId(nm.getDeptId() != null ? nm.getDeptId() : templateItem.getDeptId());
        newItem.setBizType(templateItem.getBizType());
        newItem.setRoleType(nm.getRoleType());
        newItem.setFeeItem(templateItem.getFeeItem());
        newItem.setAmount(newAmount);
        newItem.setStatus(ItemStatus.APPROVED);
        newItem.setAdjustId(adjust.getId());
        itemMapper.insert(newItem);

        applicationService.recalcAggregates(adjust.getApplicationId(), null);
        log.info("[结佣-调整-ADD_MEMBER] 执行完成：adjustId={}, applicationId={}, newEmployeeId={}, newAmount={}",
            adjust.getId(), adjust.getApplicationId(), nm.getEmployeeId(), round2(newAmount));
    }

    /** 解析调整单快照，解析失败返回 null（合同级旧单无快照时走原分摊逻辑兜底）。 */
    private CommissionAdjustPayload parsePayload(String payloadJson) {
        if (StringUtils.isBlank(payloadJson)) {
            return null;
        }
        try {
            return JsonUtils.parseObject(payloadJson, CommissionAdjustPayload.class);
        } catch (Exception e) {
            log.warn("[结佣-调整] 快照解析失败，按原分摊逻辑兜底：payload={}", payloadJson, e);
            return null;
        }
    }

    /**
     * 业绩冲销（VOID）：冲销绑定事实并同步另一口径，结佣明细置 REVERSED。
     * 合同级跨月查找（明细绑定的可能是早于申请单月份的新签事实）。
     */
    private void executeVoidAdjust(CommissionAdjust adjust, Long approverId) {
        if (SCOPE_CONTRACT.equals(adjust.getAdjustScope())) {
            // 结佣口径=新签：合同级冲销只冲 PERF_EXPECT 事实，不动实收（实收为客观到账，不随结佣调整）
            for (PerformanceFactSummaryDTO f : performanceQueryPort.findActiveByBizKeys(
                contractBizKeys(adjust.getApplicationId(), adjust.getContractNo()), FACT_TYPE_EXPECT)) {
                performanceQueryPort.voidFact(f.getFactId(), approverId, adjust.getId());
            }
            for (CommissionItem item : listActiveItems(adjust.getApplicationId())) {
                reverseItem(item, adjust.getId());
            }
            applicationService.recalcAggregates(adjust.getApplicationId(), null);
            log.info("[结佣-调整-VOID-合同级] 完成（仅冲新签）：adjustId={}", adjust.getId());
        } else {
            CommissionItem item = itemMapper.selectById(adjust.getItemId());
            requireItem(item, adjust);
            if (boundFactIsExpect(item)) {
                // 新口径：直接冲销绑定的新签事实
                performanceQueryPort.voidFact(item.getPerformanceFactId(), approverId, adjust.getId());
            } else {
                // 历史单：冲销实收事实 + 同员工应收同步冲销
                performanceQueryPort.voidFact(item.getPerformanceFactId(), approverId, adjust.getId());
                PerformanceFactSummaryDTO expectFact = findExpectByEmployee(
                    adjust.getPeriod(), adjust.getContractNo(), item.getEmployeeId());
                if (expectFact != null) {
                    performanceQueryPort.voidFact(expectFact.getFactId(), approverId, adjust.getId());
                }
            }
            reverseItem(item, adjust.getId());
            applicationService.recalcAggregates(adjust.getApplicationId(), null);
            log.info("[结佣-调整-VOID-明细级] 完成：adjustId={}, itemId={}", adjust.getId(), item.getId());
        }
    }

    /**
     * 部门划转（TRANSFER）：划转绑定事实部门并同步另一口径，回写 CommissionItem.deptId。
     * 合同级跨月查找（明细绑定的可能是早于申请单月份的新签事实）。
     */
    private void executeTransferAdjust(CommissionAdjust adjust, Long approverId) {
        Long targetDeptId = adjust.getTargetDeptId();
        if (SCOPE_CONTRACT.equals(adjust.getAdjustScope())) {
            // 结佣口径=新签：合同级划转只划转 PERF_EXPECT 事实部门，不动实收
            for (PerformanceFactSummaryDTO f : performanceQueryPort.findActiveByBizKeys(
                contractBizKeys(adjust.getApplicationId(), adjust.getContractNo()), FACT_TYPE_EXPECT)) {
                performanceQueryPort.transferFact(f.getFactId(), targetDeptId, approverId, adjust.getId());
            }
            for (CommissionItem item : listActiveItems(adjust.getApplicationId())) {
                item.setDeptId(targetDeptId);
                item.setAdjustId(adjust.getId());
                itemMapper.updateById(item);
            }
            log.info("[结佣-调整-TRANSFER-合同级] 完成（仅划新签）：adjustId={}, targetDeptId={}", adjust.getId(), targetDeptId);
        } else {
            CommissionItem item = itemMapper.selectById(adjust.getItemId());
            requireItem(item, adjust);
            if (boundFactIsExpect(item)) {
                // 新口径：划转绑定的新签事实 + 同员工实收事实同步划转（两口径部门保持一致）
                performanceQueryPort.transferFact(item.getPerformanceFactId(), targetDeptId, approverId, adjust.getId());
                PerformanceFactSummaryDTO realFact = findRealByEmployee(
                    adjust.getPeriod(), adjust.getContractNo(), item.getEmployeeId());
                if (realFact != null) {
                    performanceQueryPort.transferFact(realFact.getFactId(), targetDeptId, approverId, adjust.getId());
                }
            } else {
                // 历史单：划转实收事实 + 同员工应收同步划转
                performanceQueryPort.transferFact(item.getPerformanceFactId(), targetDeptId, approverId, adjust.getId());
                PerformanceFactSummaryDTO expectFact = findExpectByEmployee(
                    adjust.getPeriod(), adjust.getContractNo(), item.getEmployeeId());
                if (expectFact != null) {
                    performanceQueryPort.transferFact(expectFact.getFactId(), targetDeptId, approverId, adjust.getId());
                }
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

    /** 按同合同+同员工匹配 PERF_REAL 事实（明细级同步用，新口径明细绑定期望事实时对偶同步）。 */
    private PerformanceFactSummaryDTO findRealByEmployee(String period, String contractNo, Long employeeId) {
        if (employeeId == null) {
            return null;
        }
        return performanceQueryPort.findActiveByContract(period, contractNo, FACT_TYPE_REAL).stream()
            .filter(f -> employeeId.equals(f.getEmployeeId()))
            .findFirst().orElse(null);
    }

    /** 明细绑定的事实是否为 PERF_EXPECT（2026-09-27 新口径单）；false = PERF_REAL（历史单）。 */
    private boolean boundFactIsExpect(CommissionItem item) {
        PerformanceFactSummaryDTO bound = performanceQueryPort.getByFactId(item.getPerformanceFactId());
        return bound != null && FACT_TYPE_EXPECT.equals(bound.getFactType());
    }

    /**
     * 合同级调整的业务键集合：订单号 + 合同号双键（跨月查找事实用），
     * 订单号从申请单取（历史单/新单均覆盖）。
     */
    private Set<String> contractBizKeys(Long applicationId, String contractNo) {
        Set<String> keys = new HashSet<>();
        if (StringUtils.isNotBlank(contractNo)) {
            keys.add(contractNo);
        }
        CommissionApplication app = applicationMapper.selectById(applicationId);
        if (app != null && StringUtils.isNotBlank(app.getOrderNo())) {
            keys.add(app.getOrderNo());
        }
        if (keys.isEmpty()) {
            keys.add(contractNo);
        }
        return keys;
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
