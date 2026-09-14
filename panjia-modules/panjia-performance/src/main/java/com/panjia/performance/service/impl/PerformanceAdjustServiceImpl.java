package com.panjia.performance.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.performance.domain.AdjustStatus;
import com.panjia.performance.domain.AdjustType;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.IllegalStateTransitionException;
import com.panjia.performance.domain.PerformanceAdjust;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.ReversedReason;
import com.panjia.performance.dto.AdjustCreateDTO;
import com.panjia.performance.dto.AdjustDetailDTO;
import com.panjia.performance.dto.AdjustFactDetailDTO;
import com.panjia.performance.dto.AdjustQuery;
import com.panjia.performance.mapper.PerformanceAdjustMapper;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.service.PerformanceAdjustService;
import com.panjia.performance.service.ReverseService;
import com.panjia.performance.util.MoneyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.workflow.api.WorkflowService;
import org.dromara.workflow.api.domain.FlowInstanceBizExtDTO;
import org.dromara.workflow.api.domain.StartProcessDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 业绩调整单服务实现。
 * <p>
 * 审批全走 RuoYi 自带工作流（flowCode = perf_adjust）：
 * <ol>
 *   <li>{@link #createAdjust} 落库后调用 {@link WorkflowService#startCompleteTask} 发起审批；</li>
 *   <li>审批结果由 {@code AdjustWorkflowListener} 监听 ProcessEvent 回调
 *       {@link #handleWorkflowEvent}：finish → 自动执行调整并置 EXECUTED；
 *       invalid/termination → REJECTED；cancel → CANCELLED。</li>
 * </ol>
 * 调整范围：
 * <ul>
 *   <li>CONTRACT（合同级）：仅支持金额调整，按各明细 performance_amount 占比分摊，
 *       尾差补到金额最大的一条，逐条 supersede；</li>
 *   <li>DETAIL（明细级）：金额调整 / 业绩冲销 / 部门划转，作用于单条事实。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceAdjustServiceImpl implements PerformanceAdjustService {

    /** 调整单号前缀 */
    private static final String ADJUST_NO_PREFIX = "ADJ";
    /** 调整单号日期格式 */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 业绩调整审批流编码（flow_definition.flow_code） */
    private static final String FLOW_CODE_PERF_ADJUST = "perf_adjust";

    /** 调整范围：合同级 */
    private static final String SCOPE_CONTRACT = "CONTRACT";
    /** 调整范围：明细级 */
    private static final String SCOPE_DETAIL = "DETAIL";

    /** §4.1 业绩调整只允许改应收口径 */
    private static final String FACT_TYPE_EXPECT = "PERF_EXPECT";

    /** 工作流状态：审批通过（BusinessStatusEnum.finish） */
    private static final String WF_STATUS_FINISH = "finish";
    /** 工作流状态：驳回（REJECT 边回调，与实收/结佣一致 status=back） */
    private static final String WF_STATUS_BACK = "back";
    /** 工作流状态：作废（BusinessStatusEnum.invalid） */
    private static final String WF_STATUS_INVALID = "invalid";
    /** 工作流状态：终止（BusinessStatusEnum.termination） */
    private static final String WF_STATUS_TERMINATION = "termination";
    /** 工作流状态：撤销（BusinessStatusEnum.cancel） */
    private static final String WF_STATUS_CANCEL = "cancel";

    private final PerformanceAdjustMapper adjustMapper;
    private final PerformanceFactMapper factMapper;
    private final ReverseService reverseService;
    private final WorkflowService workflowService;

    @Override
    public PageResult<PerformanceAdjust> listAdjusts(AdjustQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<PerformanceAdjust> wrapper = buildQueryWrapper(query);
        wrapper.orderByDesc(PerformanceAdjust::getCreateTime);

        Page<PerformanceAdjust> page = adjustMapper.selectPage(pageQuery.build(), wrapper);
        List<PerformanceAdjust> records = page.getRecords();
        // 批量回填员工姓名 / 部门名称（含目标部门），避免列表显示裸 ID
        fillDisplayNames(records);
        return PageResult.build(records, page.getTotal());
    }

    /**
     * 批量回填展示名称：员工姓名（pj_people_employee）、原部门名、目标部门名（sys_dept）。
     * 空集合安全，两次 IN 查询无 N+1。
     */
    private void fillDisplayNames(List<PerformanceAdjust> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Set<Long> employeeIds = records.stream()
            .map(PerformanceAdjust::getEmployeeId).filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        Set<Long> deptIds = new HashSet<>();
        for (PerformanceAdjust r : records) {
            if (r.getDeptId() != null) {
                deptIds.add(r.getDeptId());
            }
            if (r.getTargetDeptId() != null) {
                deptIds.add(r.getTargetDeptId());
            }
        }

        Map<Long, String> empNameMap = new HashMap<>();
        for (Map<String, Object> row : adjustMapper.employeeNames(employeeIds.stream().toList())) {
            empNameMap.put(((Number) row.get("employeeId")).longValue(), String.valueOf(row.get("employeeName")));
        }
        Map<Long, String> deptNameMap = new HashMap<>();
        for (Map<String, Object> row : adjustMapper.deptNames(deptIds.stream().toList())) {
            deptNameMap.put(((Number) row.get("deptId")).longValue(), String.valueOf(row.get("deptName")));
        }

        for (PerformanceAdjust r : records) {
            if (r.getEmployeeId() != null) {
                r.setEmployeeName(empNameMap.get(r.getEmployeeId()));
            }
            if (r.getDeptId() != null) {
                r.setDeptName(deptNameMap.get(r.getDeptId()));
            }
            if (r.getTargetDeptId() != null) {
                r.setTargetDeptName(deptNameMap.get(r.getTargetDeptId()));
            }
        }
        fillCurrentAmounts(records);
    }

    /**
     * 回填调整标的当前金额（performance_amount 口径）：明细级取关联事实金额（一次 IN 批量），
     * 合同级汇总该合同下全部 ACTIVE 事实金额。合同级行数少，逐行查询无 N+1 风险。
     */
    private void fillCurrentAmounts(List<PerformanceAdjust> records) {
        // 明细级：批量取
        List<Long> factIds = records.stream()
            .map(PerformanceAdjust::getFactId).filter(java.util.Objects::nonNull)
            .toList();
        Map<Long, java.math.BigDecimal> factAmountMap = new HashMap<>();
        for (Map<String, Object> row : adjustMapper.selectFactAmountsByIdsSafe(factIds)) {
            factAmountMap.put(((Number) row.get("factId")).longValue(),
                (java.math.BigDecimal) row.get("amount"));
        }
        for (PerformanceAdjust r : records) {
            if (r.getFactId() != null) {
                r.setCurrentAmount(factAmountMap.get(r.getFactId()));
            } else if (StringUtils.isNotBlank(r.getContractNo())
                && r.getFactType() != null && StringUtils.isNotBlank(r.getPeriod())) {
                // 合同级：跨月调整取原月
                String loadPeriod = StringUtils.isNotBlank(r.getOriginalPeriod())
                    ? r.getOriginalPeriod() : r.getPeriod();
                r.setCurrentAmount(adjustMapper.selectContractTotalAmount(
                    loadPeriod, r.getFactType(), r.getContractNo()));
            }
        }
    }

    @Override
    public PerformanceAdjust getAdjust(Long id) {
        PerformanceAdjust adjust = adjustMapper.selectById(id);
        if (adjust != null) {
            fillDisplayNames(List.of(adjust));
        }
        return adjust;
    }

    @Override
    public AdjustDetailDTO getAdjustDetail(Long id) {
        PerformanceAdjust adjust = getAdjust(id);
        if (adjust == null) {
            return null;
        }
        AdjustDetailDTO dto = new AdjustDetailDTO();
        // 拷贝基础字段
        org.springframework.beans.BeanUtils.copyProperties(adjust, dto);

        boolean isContractScope = SCOPE_CONTRACT.equals(adjust.getAdjustScope());
        String contractNo = adjust.getContractNo();
        Long factId = adjust.getFactId();
        String period = adjust.getPeriod();

        // 1. 查合同信息
        java.util.Map<String, Object> contractInfo = null;
        if (isContractScope && StringUtils.isNotBlank(contractNo)) {
            contractInfo = factMapper.selectContractInfoByContractNo(period, contractNo);
        } else if (factId != null) {
            contractInfo = factMapper.selectContractInfoByFactId(factId);
            if (contractInfo != null) {
                dto.setContractNo((String) contractInfo.get("contractNo"));
            }
        }

        if (contractInfo != null) {
            dto.setOrderNo(toStringOrNull(contractInfo.get("orderNo")));
            dto.setPropertyAddress(toStringOrNull(contractInfo.get("propertyAddress")));
            dto.setBusinessDate(toStringOrNull(contractInfo.get("businessDate")));
        }

        // 2. 查明细列表
        String targetContractNo = isContractScope ? contractNo : dto.getContractNo();
        String factType = adjust.getFactType();
        if (StringUtils.isBlank(factType)) {
            factType = "PERF_EXPECT"; // 默认应收口径
        }
        if (StringUtils.isNotBlank(targetContractNo)) {
            List<AdjustFactDetailDTO> details =
                factMapper.selectAdjustFactDetails(period, targetContractNo, factType);
            // 计算变动金额
            BigDecimal delta = adjust.getDeltaAmount() != null ? adjust.getDeltaAmount() : BigDecimal.ZERO;
            boolean isExpectType = "PERF_EXPECT".equals(factType);

            if (isContractScope) {
                // 合同级：按金额占比分摊 delta
                BigDecimal total = details.stream()
                    .filter(d -> d.getAmount() != null)
                    .map(AdjustFactDetailDTO::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal remain = delta;
                int maxIdx = -1;
                BigDecimal maxAmt = BigDecimal.ZERO;
                for (int i = 0; i < details.size(); i++) {
                    var d = details.get(i);
                    BigDecimal amt = d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO;
                    BigDecimal shareDelta = total.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : delta.multiply(amt).divide(total, 2, java.math.RoundingMode.HALF_UP);
                    d.setDeltaAmount(shareDelta);
                    d.setAfterAmount(amt.add(shareDelta));
                    d.setTarget(true);
                    remain = remain.subtract(shareDelta);
                    if (amt.compareTo(maxAmt) > 0) {
                        maxAmt = amt;
                        maxIdx = i;
                    }
                }
                // 尾差补到最大金额行
                if (maxIdx >= 0 && remain.compareTo(BigDecimal.ZERO) != 0) {
                    var maxRow = details.get(maxIdx);
                    maxRow.setDeltaAmount(maxRow.getDeltaAmount().add(remain));
                    maxRow.setAfterAmount(maxRow.getAfterAmount().add(remain));
                }
            } else {
                // 明细级：只标记目标行
                for (var d : details) {
                    BigDecimal amt = d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO;
                    if (d.getFactId() != null && d.getFactId().equals(factId)) {
                        d.setDeltaAmount(delta);
                        d.setAfterAmount(amt.add(delta));
                        d.setTarget(true);
                    } else {
                        d.setDeltaAmount(BigDecimal.ZERO);
                        d.setAfterAmount(amt);
                        d.setTarget(false);
                    }
                }
            }
            dto.setDetails(details);
            dto.setDetailCount(details.size());
            // 根据调整口径正确计算应收合计和实收合计
            if (isExpectType) {
                // 应收调整：amount=应收，expectedAmount=实收
                dto.setExpectedTotal(details.stream()
                    .map(d -> d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
                dto.setReceivedTotal(details.stream()
                    .map(d -> d.getExpectedAmount() != null ? d.getExpectedAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            } else {
                // 实收调整：amount=实收，expectedAmount=应收
                dto.setExpectedTotal(details.stream()
                    .map(d -> d.getExpectedAmount() != null ? d.getExpectedAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
                dto.setReceivedTotal(details.stream()
                    .map(d -> d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            }
            // 设置目标总金额
            if (adjust.getDeltaAmount() != null && isExpectType) {
                dto.setTargetAmount(dto.getExpectedTotal().add(adjust.getDeltaAmount()));
            } else if (adjust.getDeltaAmount() != null) {
                dto.setTargetAmount(dto.getReceivedTotal().add(adjust.getDeltaAmount()));
            }
        }

        return dto;
    }

    private String toStringOrNull(Object obj) {
        return obj == null ? null : String.valueOf(obj);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PerformanceAdjust createAdjust(AdjustCreateDTO dto, Long applicantId) {
        // 1. 校验调整类型
        AdjustType adjustType = AdjustType.fromCode(dto.getAdjustType());
        if (adjustType == null) {
            throw new ServiceException("非法调整类型：{}", dto.getAdjustType());
        }
        String scope = StringUtils.isBlank(dto.getAdjustScope()) ? SCOPE_DETAIL : dto.getAdjustScope();
        // §4.1 业绩调整只改应收（PERF_EXPECT）：合同级 / 明细级均拦截
        if (!FACT_TYPE_EXPECT.equals(dto.getFactType())) {
            throw new ServiceException("业绩调整仅允许调整应收业绩（PERF_EXPECT），实收业绩请走实收审批/结佣对齐流程");
        }
        if (SCOPE_CONTRACT.equals(scope)) {
            // 合同级当前仅支持金额调整（按占比分摊）
            if (adjustType != AdjustType.AMOUNT) {
                throw new ServiceException("合同级调整仅支持金额调整");
            }
            if (StringUtils.isBlank(dto.getContractNo()) || StringUtils.isBlank(dto.getFactType())) {
                throw new ServiceException("合同级调整缺少合同号或事实口径");
            }
        } else if (dto.getFactId() == null) {
            throw new ServiceException("明细级调整缺少关联业绩事实");
        }

        // 2. 目标金额 → 增量换算（优先用 targetAmount，兼容旧的 deltaAmount）
        if (adjustType == AdjustType.AMOUNT && dto.getTargetAmount() != null) {
            BigDecimal currentAmount = calculateCurrentAmount(dto, scope);
            BigDecimal delta = MoneyUtil.round2(dto.getTargetAmount().subtract(currentAmount));
            dto.setDeltaAmount(delta);
        }

        // 3. 构建调整单
        PerformanceAdjust adjust = new PerformanceAdjust();
        adjust.setAdjustNo(generateAdjustNo());
        adjust.setFactId(dto.getFactId());
        adjust.setPeriod(dto.getPeriod());
        adjust.setEmployeeId(dto.getEmployeeId());
        adjust.setDeptId(dto.getDeptId());
        adjust.setAdjustType(adjustType);
        adjust.setAdjustScope(scope);
        adjust.setContractNo(dto.getContractNo());
        adjust.setFactType(dto.getFactType());
        adjust.setOriginalPeriod(dto.getOriginalPeriod());
        adjust.setDeltaAmount(dto.getDeltaAmount());
        adjust.setTargetDeptId(dto.getTargetDeptId());
        adjust.setReason(dto.getReason());
        adjust.setPayloadJson(dto.getPayloadJson());
        adjust.setStatus(AdjustStatus.SUBMITTED);
        adjust.setApplicantId(applicantId);

        // 合同级调整前端可能未传员工/部门（合同聚合行无此信息），从该合同首条 ACTIVE 事实回填
        // 跨月调整时事实在 originalPeriod（原业绩归属月）
        if (SCOPE_CONTRACT.equals(scope)
            && (dto.getDeptId() == null || dto.getDeptId() <= 0 || dto.getEmployeeId() == null)) {
            String factLoadPeriod = StringUtils.isNotBlank(dto.getOriginalPeriod())
                ? dto.getOriginalPeriod() : dto.getPeriod();
            List<PerformanceFact> facts = factMapper.selectActiveFactsByContractNo(
                factLoadPeriod, dto.getFactType(), dto.getContractNo());
            if (facts == null || facts.isEmpty()) {
                throw new ServiceException("合同下未找到有效业绩事实，无法发起调整：{}", dto.getContractNo());
            }
            if (adjust.getDeptId() == null || adjust.getDeptId() <= 0) {
                adjust.setDeptId(facts.get(0).getDeptId());
            }
            if (adjust.getEmployeeId() == null) {
                adjust.setEmployeeId(facts.get(0).getEmployeeId());
            }
        }

        // 明细级调整：员工/部门缺省从关联事实回填；同时前置校验事实存在、口径为应收、状态有效（§4.1）
        if (SCOPE_DETAIL.equals(scope)
            && (dto.getEmployeeId() == null || dto.getDeptId() == null || dto.getDeptId() <= 0)) {
            PerformanceFact refFact = factMapper.selectById(dto.getFactId());
            if (refFact == null) {
                throw new ServiceException("关联业绩事实不存在：factId={}", dto.getFactId());
            }
            if (refFact.getFactType() == null
                || !FACT_TYPE_EXPECT.equals(refFact.getFactType().getCode())) {
                throw new ServiceException("业绩调整仅允许调整应收业绩（PERF_EXPECT），实收业绩请走实收审批/结佣对齐流程");
            }
            if (refFact.getFactStatus() != FactStatus.ACTIVE) {
                throw new ServiceException("关联业绩事实非有效状态，无法调整：factId={}, status={}",
                    dto.getFactId(), refFact.getFactStatus().getCode());
            }
            if (adjust.getEmployeeId() == null) {
                adjust.setEmployeeId(refFact.getEmployeeId());
            }
            if (adjust.getDeptId() == null || adjust.getDeptId() <= 0) {
                adjust.setDeptId(refFact.getDeptId());
            }
        }

        adjustMapper.insert(adjust);

        // 3. 发起 RuoYi 工作流审批（businessId=调整单ID），失败则整体回滚
        StartProcessDTO startProcess = new StartProcessDTO();
        startProcess.setBusinessId(String.valueOf(adjust.getId()));
        startProcess.setFlowCode(FLOW_CODE_PERF_ADJUST);
        Map<String, Object> variables = new HashMap<>(2);
        // 后端发起无登录用户上下文，忽略权限
        variables.put("ignore", true);
        startProcess.setVariables(variables);
        startProcess.setBizExt(buildBizExt(adjust));

        boolean started;
        try {
            started = workflowService.startCompleteTask(startProcess);
        } catch (Exception e) {
            log.error("[调整单] 审批流程发起异常：adjustId={}", adjust.getId(), e);
            throw new ServiceException("业绩调整审批流程发起失败：{}", e.getMessage());
        }
        if (!started) {
            throw new ServiceException("业绩调整审批流程发起失败");
        }

        // 4. 回填流程实例 ID
        try {
            Long instanceId = workflowService.getInstanceIdByBusinessId(String.valueOf(adjust.getId()));
            if (instanceId != null) {
                adjust.setProcessInstanceId(String.valueOf(instanceId));
                adjustMapper.updateById(adjust);
            }
        } catch (Exception e) {
            // 实例 ID 回填失败不阻断主流程（回调以 businessId 路由），仅留痕
            log.warn("[调整单] 流程实例ID回填失败：adjustId={}", adjust.getId(), e);
        }

        log.info("[调整单] 创建并提交审批成功：adjustId={}, adjustNo={}, scope={}, type={}, applicantId={}",
            adjust.getId(), adjust.getAdjustNo(), scope, adjustType.getCode(), applicantId);
        return adjust;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleWorkflowEvent(Long adjustId, String status, String handler, String message) {
        PerformanceAdjust adjust = adjustMapper.selectById(adjustId);
        if (adjust == null) {
            log.warn("[调整单工作流] 调整单不存在，忽略回调：adjustId={}, status={}", adjustId, status);
            return;
        }
        Long handlerId = parseHandlerId(handler);

        switch (status == null ? "" : status) {
            case WF_STATUS_FINISH -> {
                // 审批通过 → 自动执行调整（内含 SUBMITTED 状态校验，天然幂等）
                log.info("[调整单工作流] 审批通过，执行调整：adjustId={}, handler={}, message={}",
                    adjustId, handler, message);
                executeAdjust(adjustId, handlerId);
            }
            case WF_STATUS_BACK -> {
                // 总监驳回 → 调整单 REJECTED（与实收/结佣驳回语义一致）
                if (adjust.getStatus() != AdjustStatus.SUBMITTED) {
                    log.info("[调整单工作流] 非提交态，忽略驳回回调：adjustId={}, current={}",
                        adjustId, adjust.getStatus());
                    return;
                }
                adjust.setStatus(AdjustStatus.REJECTED);
                adjust.setApproverId(handlerId);
                adjust.setApproveTime(LocalDateTime.now());
                adjustMapper.updateById(adjust);
                log.info("[调整单工作流] 总监驳回，调整单置 REJECTED：adjustId={}, message={}",
                    adjustId, message);
            }
            case WF_STATUS_INVALID, WF_STATUS_TERMINATION -> {
                if (adjust.getStatus() != AdjustStatus.SUBMITTED) {
                    log.info("[调整单工作流] 非提交态，忽略作废/终止回调：adjustId={}, current={}",
                        adjustId, adjust.getStatus());
                    return;
                }
                adjust.setStatus(AdjustStatus.REJECTED);
                adjust.setApproverId(handlerId);
                adjust.setApproveTime(LocalDateTime.now());
                adjustMapper.updateById(adjust);
                log.info("[调整单工作流] 流程作废/终止，调整单置 REJECTED：adjustId={}, message={}",
                    adjustId, message);
            }
            case WF_STATUS_CANCEL -> {
                if (adjust.getStatus() != AdjustStatus.SUBMITTED) {
                    log.info("[调整单工作流] 非提交态，忽略撤销回调：adjustId={}, current={}",
                        adjustId, adjust.getStatus());
                    return;
                }
                adjust.setStatus(AdjustStatus.CANCELLED);
                adjust.setOperatorId(handlerId);
                adjustMapper.updateById(adjust);
                log.info("[调整单工作流] 流程撤销，调整单置 CANCELLED：adjustId={}, message={}",
                    adjustId, message);
            }
            default -> log.info("[调整单工作流] 无需处理的状态，忽略：adjustId={}, status={}", adjustId, status);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelAdjust(Long id, Long operatorId) {
        PerformanceAdjust adjust = getAndCheck(id);
        checkTransition(adjust.getStatus(), AdjustStatus.CANCELLED, "调整单");

        // 终止运行中的审批流程实例（触发 cancel 事件，监听器幂等置 CANCELLED），与实收/结佣作废一致
        if (StringUtils.isNotBlank(adjust.getProcessInstanceId())) {
            try {
                workflowService.deleteInstance(List.of(String.valueOf(id)));
            } catch (Exception e) {
                log.warn("[调整单] 取消时终止流程实例失败，按业务取消继续：adjustId={}", id, e);
            }
            PerformanceAdjust latest = adjustMapper.selectById(id);
            if (latest != null && latest.getStatus() == AdjustStatus.CANCELLED) {
                log.info("[调整单] 取消（流程事件已置 CANCELLED）：adjustId={}, operatorId={}", id, operatorId);
                return;
            }
            adjust = latest != null ? latest : adjust;
        }

        adjust.setStatus(AdjustStatus.CANCELLED);
        adjust.setOperatorId(operatorId);
        adjustMapper.updateById(adjust);

        log.info("[调整单] 取消：adjustId={}, operatorId={}", id, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void executeAdjust(Long id, Long operatorId) {
        PerformanceAdjust adjust = getAndCheck(id);
        checkTransition(adjust.getStatus(), AdjustStatus.EXECUTED, "调整单");

        // 根据调整范围 + 类型执行不同逻辑
        if (SCOPE_CONTRACT.equals(adjust.getAdjustScope())) {
            // 合同级仅 AMOUNT：原月=调整月走金额调整；跨月走业绩冲销（§4.6）
            String originalPeriod = StringUtils.isNotBlank(adjust.getOriginalPeriod())
                ? adjust.getOriginalPeriod() : adjust.getPeriod();
            if (originalPeriod.equals(adjust.getPeriod())) {
                executeContractAmountAdjust(adjust, operatorId);
            } else {
                executeContractCrossMonthAdjust(adjust, originalPeriod, operatorId);
            }
        } else {
            PerformanceFact oldFact = getActiveFact(adjust);
            boolean crossMonth = !oldFact.getPeriod().equals(adjust.getPeriod());
            switch (adjust.getAdjustType()) {
                case AMOUNT -> {
                    if (crossMonth) {
                        executeDetailCrossMonthAmountAdjust(adjust, oldFact, operatorId);
                    } else {
                        executeAmountAdjust(adjust, operatorId);
                    }
                }
                case VOID -> {
                    if (crossMonth) {
                        executeDetailCrossMonthVoidAdjust(adjust, oldFact, operatorId);
                    } else {
                        executeVoidAdjust(adjust, operatorId);
                    }
                }
                case TRANSFER -> {
                    if (crossMonth) {
                        throw new ServiceException("部门划转仅支持在业绩原归属月内调整，不支持跨月");
                    }
                    executeTransferAdjust(adjust, operatorId);
                }
                default -> throw new ServiceException("不支持的调整类型：{}", adjust.getAdjustType());
            }
        }

        // 更新状态为已执行
        adjust.setStatus(AdjustStatus.EXECUTED);
        adjust.setOperatorId(operatorId);
        adjust.setExecuteTime(LocalDateTime.now());
        adjustMapper.updateById(adjust);

        log.info("[调整单] 执行完成：adjustId={}, scope={}, type={}, operatorId={}",
            id, adjust.getAdjustScope(), adjust.getAdjustType().getCode(), operatorId);
    }

    // ==================== 内部方法 ====================

    /**
     * 构建流程业务扩展信息，供「我的待办 / 我发起的」列表直接展示"在审什么"。
     */
    private FlowInstanceBizExtDTO buildBizExt(PerformanceAdjust adjust) {
        FlowInstanceBizExtDTO bizExt = new FlowInstanceBizExtDTO();
        bizExt.setBusinessId(String.valueOf(adjust.getId()));
        bizExt.setBusinessCode(text(adjust.getAdjustNo()));
        bizExt.setBusinessTitle("业绩调整｜单号" + text(adjust.getAdjustNo())
            + "｜账期" + text(adjust.getPeriod())
            + "｜合同" + text(adjust.getContractNo())
            + "｜类型" + text(adjust.getAdjustType())
            + "｜差额" + text(adjust.getDeltaAmount()));
        return bizExt;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 构建查询条件。
     */
    private LambdaQueryWrapper<PerformanceAdjust> buildQueryWrapper(AdjustQuery query) {
        LambdaQueryWrapper<PerformanceAdjust> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.isNotBlank(query.getPeriod()),
            PerformanceAdjust::getPeriod, query.getPeriod());
        wrapper.eq(StringUtils.isNotBlank(query.getAdjustType()),
            PerformanceAdjust::getAdjustType, AdjustType.fromCode(query.getAdjustType()));
        wrapper.eq(StringUtils.isNotBlank(query.getStatus()),
            PerformanceAdjust::getStatus, AdjustStatus.fromCode(query.getStatus()));
        wrapper.eq(query.getEmployeeId() != null,
            PerformanceAdjust::getEmployeeId, query.getEmployeeId());
        wrapper.eq(query.getDeptId() != null,
            PerformanceAdjust::getDeptId, query.getDeptId());
        return wrapper;
    }

    /**
     * 查询并校验调整单存在。
     */
    private PerformanceAdjust getAndCheck(Long id) {
        PerformanceAdjust adjust = adjustMapper.selectById(id);
        if (adjust == null) {
            throw new ServiceException("调整单不存在：id={}", id);
        }
        return adjust;
    }

    /**
     * 校验状态是否可流转。
     *
     * @param current 当前状态
     * @param target  目标状态
     * @param entity  实体名称
     * @throws IllegalStateTransitionException 非法状态转换
     */
    private void checkTransition(AdjustStatus current, AdjustStatus target, String entity) {
        if (!current.canTransitTo(target)) {
            throw new IllegalStateTransitionException(current.getCode(), target.getCode(), entity);
        }
    }

    /**
     * 生成调整单号：ADJ + yyyyMMdd + 6位随机数。
     *
     * @return 调整单号
     */
    private String generateAdjustNo() {
        String datePart = LocalDate.now().format(DATE_FORMATTER);
        String randomPart = RandomUtil.randomNumbers(6);
        return ADJUST_NO_PREFIX + datePart + randomPart;
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

    /**
     * 执行合同级金额调整：按各明细 performance_amount 占比分摊总调整额，逐条 supersede。
     * <p>
     * 分摊后各条调整额之和可能与总调整额存在分位尾差，尾差补到业绩金额绝对值最大的一条，
     * 保证 Σ新业绩 = Σ旧业绩 + deltaAmount 精确成立。
     */
    private void executeContractAmountAdjust(PerformanceAdjust adjust, Long operatorId) {
        if (adjust.getDeltaAmount() == null) {
            throw new ServiceException("合同级金额调整缺少调整金额：adjustId={}", adjust.getId());
        }
        List<PerformanceFact> facts = factMapper.selectActiveFactsByContractNo(
            adjust.getPeriod(), adjust.getFactType(), adjust.getContractNo());
        if (facts == null || facts.isEmpty()) {
            throw new ServiceException("合同下未找到有效业绩事实：contractNo={}", adjust.getContractNo());
        }

        BigDecimal total = facts.stream()
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (MoneyUtil.isZero(total)) {
            throw new ServiceException("合同业绩金额合计为 0，无法按比例分摊：contractNo={}",
                adjust.getContractNo());
        }

        BigDecimal deltaTotal = adjust.getDeltaAmount();
        BigDecimal[] parts = new BigDecimal[facts.size()];
        BigDecimal allocated = BigDecimal.ZERO;
        int largestIdx = 0;
        for (int i = 0; i < facts.size(); i++) {
            PerformanceFact fact = facts.get(i);
            BigDecimal base = fact.getPerformanceAmount() == null ? BigDecimal.ZERO : fact.getPerformanceAmount();
            // 按业绩占比分摊（比例中间值保留 8 位，金额最终 round2）
            parts[i] = MoneyUtil.round2(
                deltaTotal.multiply(base).divide(total, 8, RoundingMode.HALF_UP));
            allocated = allocated.add(parts[i]);
            if (base.abs().compareTo(facts.get(largestIdx).getPerformanceAmount().abs()) > 0) {
                largestIdx = i;
            }
        }
        // 尾差补到金额最大的一条
        parts[largestIdx] = MoneyUtil.round2(parts[largestIdx].add(deltaTotal.subtract(allocated)));

        int affected = 0;
        for (int i = 0; i < facts.size(); i++) {
            if (MoneyUtil.isZero(parts[i])) {
                continue;
            }
            PerformanceFact oldFact = facts.get(i);
            PerformanceFact newFact = buildContractAdjustedFact(oldFact, parts[i], adjust.getId());
            reverseService.supersede(oldFact.getId(), newFact, operatorId);
            affected++;
        }
        log.info("[调整单] 合同级金额调整完成：adjustId={}, contractNo={}, 明细数={}, 实际调整条数={}, delta={}",
            adjust.getId(), adjust.getContractNo(), facts.size(), affected, deltaTotal);
    }

    /**
     * 构建合同级分摊后的新事实：按 performance_amount 口径计算新金额。
     */
    private PerformanceFact buildContractAdjustedFact(PerformanceFact oldFact, BigDecimal deltaPerformance,
                                                      Long adjustId) {
        BigDecimal newPerformance = MoneyUtil.round2(
            oldFact.getPerformanceAmount().add(deltaPerformance));

        PerformanceFact newFact = copyFactBase(oldFact);
        newFact.setPerformanceAmount(newPerformance);
        newFact.setAdjustId(adjustId);
        return newFact;
    }

    /**
     * 执行合同级跨月调整（§4.6 业绩冲销）：原月事实保持不动（历史月已结算不回改），
     * 在调整月按原各人业绩占比分摊调整额，逐人生成净额调整事实（正=补提，负=冲销）。
     */
    private void executeContractCrossMonthAdjust(PerformanceAdjust adjust, String originalPeriod, Long operatorId) {
        if (adjust.getDeltaAmount() == null) {
            throw new ServiceException("合同级跨月调整缺少调整金额：adjustId={}", adjust.getId());
        }
        List<PerformanceFact> facts = factMapper.selectActiveFactsByContractNo(
            originalPeriod, adjust.getFactType(), adjust.getContractNo());
        if (facts == null || facts.isEmpty()) {
            throw new ServiceException("原月合同下未找到有效业绩事实：contractNo={}, period={}",
                adjust.getContractNo(), originalPeriod);
        }
        BigDecimal total = facts.stream()
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (MoneyUtil.isZero(total)) {
            throw new ServiceException("原月合同业绩金额合计为 0，无法按占比冲销：contractNo={}",
                adjust.getContractNo());
        }
        BigDecimal deltaTotal = adjust.getDeltaAmount();
        BigDecimal[] parts = new BigDecimal[facts.size()];
        BigDecimal allocated = BigDecimal.ZERO;
        int largestIdx = 0;
        for (int i = 0; i < facts.size(); i++) {
            PerformanceFact fact = facts.get(i);
            BigDecimal base = fact.getPerformanceAmount() == null ? BigDecimal.ZERO : fact.getPerformanceAmount();
            parts[i] = MoneyUtil.round2(
                deltaTotal.multiply(base).divide(total, 8, RoundingMode.HALF_UP));
            allocated = allocated.add(parts[i]);
            if (base.abs().compareTo(facts.get(largestIdx).getPerformanceAmount().abs()) > 0) {
                largestIdx = i;
            }
        }
        parts[largestIdx] = MoneyUtil.round2(parts[largestIdx].add(deltaTotal.subtract(allocated)));

        int generated = 0;
        for (int i = 0; i < facts.size(); i++) {
            if (MoneyUtil.isZero(parts[i])) {
                continue;
            }
            PerformanceFact oldFact = facts.get(i);
            PerformanceFact offsetFact = buildOffsetFact(oldFact, adjust.getPeriod(),
                parts[i], adjust.getId());
            factMapper.insert(offsetFact);
            generated++;
        }
        log.info("[调整单-跨月] 合同级跨月冲销完成：adjustId={}, contractNo={}, {}→{}, 生成调整事实={}",
            adjust.getId(), adjust.getContractNo(), originalPeriod, adjust.getPeriod(), generated);
    }

    /**
     * 执行明细级跨月金额调整：在调整月生成一条净额调整事实
     * （新应收-原应收的差额，正补负冲），原月事实不动。
     * <p>
     * deltaAmount 为 performance_amount 口径的目标增量。
     */
    private void executeDetailCrossMonthAmountAdjust(PerformanceAdjust adjust, PerformanceFact oldFact,
                                                     Long operatorId) {
        if (adjust.getDeltaAmount() == null) {
            throw new ServiceException("金额调整缺少调整金额：adjustId={}", adjust.getId());
        }
        BigDecimal performanceDelta = adjust.getDeltaAmount();
        if (MoneyUtil.isZero(performanceDelta)) {
            log.info("[调整单-跨月] 调整额为 0，无需生成冲销事实：adjustId={}, factId={}",
                adjust.getId(), oldFact.getId());
            return;
        }
        factMapper.insert(buildOffsetFact(oldFact, adjust.getPeriod(),
            performanceDelta, adjust.getId()));
        log.info("[调整单-跨月] 明细级跨月冲销事实已生成：adjustId={}, factId={}, {}→{}, deltaPerf={}",
            adjust.getId(), oldFact.getId(), oldFact.getPeriod(), adjust.getPeriod(), performanceDelta);
    }

    /**
     * 执行明细级跨月业绩冲销（§4.6 VOID 跨月）：在调整月生成原事实金额的相反数事实。
     */
    private void executeDetailCrossMonthVoidAdjust(PerformanceAdjust adjust, PerformanceFact oldFact,
                                                   Long operatorId) {
        factMapper.insert(buildOffsetFact(oldFact, adjust.getPeriod(),
            MoneyUtil.round2(oldFact.getPerformanceAmount().negate()),
            adjust.getId()));
        log.info("[调整单-跨月] 明细级跨月全额冲销事实已生成：adjustId={}, factId={}, {}→{}",
            adjust.getId(), oldFact.getId(), oldFact.getPeriod(), adjust.getPeriod());
    }

    /**
     * 构建跨月净额调整事实（直接插入，不冲销原月事实）：
     * 复制原事实人员/合同/系数快照，金额取净额（可负），期间取调整月，业务日期取调整月首日，
     * sourceKey 追加调整单后缀以避开原事实幂等键。
     */
    private PerformanceFact buildOffsetFact(PerformanceFact oldFact, String targetPeriod,
                                            BigDecimal performanceDelta, Long adjustId) {
        PerformanceFact newFact = copyFactBase(oldFact);
        LocalDate firstDay = YearMonth.parse(targetPeriod).atDay(1);
        newFact.setPeriod(targetPeriod);
        newFact.setBusinessDate(firstDay);
        newFact.setEffectiveDate(firstDay);
        newFact.setPerformanceAmount(MoneyUtil.round2(performanceDelta));
        newFact.setAdjustId(adjustId);
        newFact.setSourceKey(oldFact.getSourceKey() + "-ADJ-" + adjustId);
        return newFact;
    }

    /**
     * 执行明细级金额调整：旧事实冲销 + 新事实生成（performance_amount 直接 += delta）。
     */
    private void executeAmountAdjust(PerformanceAdjust adjust, Long operatorId) {
        PerformanceFact oldFact = getActiveFact(adjust);
        if (adjust.getDeltaAmount() == null) {
            throw new ServiceException("金额调整缺少调整金额：adjustId={}", adjust.getId());
        }

        BigDecimal newPerformance = MoneyUtil.round2(
            oldFact.getPerformanceAmount().add(adjust.getDeltaAmount()));

        PerformanceFact newFact = copyFactBase(oldFact);
        newFact.setPerformanceAmount(newPerformance);
        newFact.setAdjustId(adjust.getId());

        reverseService.supersede(oldFact.getId(), newFact, operatorId);
    }

    /**
     * 执行业绩冲销：单条事实冲销。
     */
    private void executeVoidAdjust(PerformanceAdjust adjust, Long operatorId) {
        reverseService.reverseByAdjust(adjust.getFactId(), adjust.getId(),
            ReversedReason.MANUAL_ADJUST, operatorId);
    }

    /**
     * 执行部门划转：旧事实冲销 + 新事实（新部门）生成。
     */
    private void executeTransferAdjust(PerformanceAdjust adjust, Long operatorId) {
        PerformanceFact oldFact = getActiveFact(adjust);
        if (adjust.getTargetDeptId() == null) {
            throw new ServiceException("部门划转调整单缺少目标部门：adjustId={}", adjust.getId());
        }

        PerformanceFact newFact = copyFactBase(oldFact);
        newFact.setDeptId(adjust.getTargetDeptId());
        newFact.setAdjustId(adjust.getId());

        reverseService.supersede(oldFact.getId(), newFact, operatorId);
    }

    /**
     * 计算调整前的当前金额（performance_amount 口径）。
     * <p>
     * 合同级：汇总该合同下全部 ACTIVE 事实的 performance_amount；
     * 明细级：取单条事实的 performance_amount。
     */
    private BigDecimal calculateCurrentAmount(AdjustCreateDTO dto, String scope) {
        String factType = dto.getFactType();
        String period = StringUtils.isNotBlank(dto.getOriginalPeriod())
            ? dto.getOriginalPeriod() : dto.getPeriod();
        if (SCOPE_CONTRACT.equals(scope)) {
            BigDecimal total = adjustMapper.selectContractTotalAmount(period, factType, dto.getContractNo());
            return total != null ? total : BigDecimal.ZERO;
        } else {
            PerformanceFact fact = factMapper.selectById(dto.getFactId());
            if (fact == null) {
                throw new ServiceException("关联业绩事实不存在：factId={}", dto.getFactId());
            }
            return fact.getPerformanceAmount() != null ? fact.getPerformanceAmount() : BigDecimal.ZERO;
        }
    }

    /**
     * 读取调整单关联事实并校验为 ACTIVE。
     */
    private PerformanceFact getActiveFact(PerformanceAdjust adjust) {
        PerformanceFact oldFact = factMapper.selectById(adjust.getFactId());
        if (oldFact == null) {
            throw new ServiceException("关联业绩事实不存在：factId={}", adjust.getFactId());
        }
        if (oldFact.getFactStatus() != FactStatus.ACTIVE) {
            throw new ServiceException("关联业绩事实非有效状态，无法调整：factId={}, status={}",
                adjust.getFactId(), oldFact.getFactStatus().getCode());
        }
        return oldFact;
    }

    /**
     * 以旧事实为模板复制新事实（supersede 用）：保留人员/金额口径/来源关联，
     * 金额字段由调用方覆盖；拷贝批次与归一化记录引用以保证调整后仍能在合同维度树中查到。
     */
    private PerformanceFact copyFactBase(PerformanceFact oldFact) {
        PerformanceFact newFact = new PerformanceFact();
        newFact.setFactType(oldFact.getFactType());
        newFact.setPeriod(oldFact.getPeriod());
        newFact.setBusinessDate(oldFact.getBusinessDate());
        newFact.setBatchId(oldFact.getBatchId());
        newFact.setNormalizedRecordId(oldFact.getNormalizedRecordId());
        newFact.setSourceKey(oldFact.getSourceKey());
        newFact.setBizType(oldFact.getBizType());
        newFact.setEmployeeId(oldFact.getEmployeeId());
        newFact.setEmployeeExternalCode(oldFact.getEmployeeExternalCode());
        newFact.setDeptId(oldFact.getDeptId());
        newFact.setRoleType(oldFact.getRoleType());
        newFact.setShareRatio(oldFact.getShareRatio());
        newFact.setPerformanceAmount(oldFact.getPerformanceAmount());
        newFact.setEffectiveDate(oldFact.getEffectiveDate() != null
            ? oldFact.getEffectiveDate() : oldFact.getBusinessDate());
        newFact.setFactStatus(FactStatus.ACTIVE);
        newFact.setSource(oldFact.getSource());
        return newFact;
    }
}
