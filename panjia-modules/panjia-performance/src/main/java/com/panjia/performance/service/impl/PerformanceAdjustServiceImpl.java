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
import com.panjia.performance.dto.AdjustQuery;
import com.panjia.performance.engine.ConversionEngine;
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
import org.dromara.workflow.api.domain.StartProcessDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    /** 工作流状态：审批通过（BusinessStatusEnum.finish） */
    private static final String WF_STATUS_FINISH = "finish";
    /** 工作流状态：作废（BusinessStatusEnum.invalid） */
    private static final String WF_STATUS_INVALID = "invalid";
    /** 工作流状态：终止（BusinessStatusEnum.termination） */
    private static final String WF_STATUS_TERMINATION = "termination";
    /** 工作流状态：撤销（BusinessStatusEnum.cancel） */
    private static final String WF_STATUS_CANCEL = "cancel";

    private final PerformanceAdjustMapper adjustMapper;
    private final PerformanceFactMapper factMapper;
    private final ReverseService reverseService;
    private final ConversionEngine conversionEngine;
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
    @Transactional(rollbackFor = Exception.class)
    public PerformanceAdjust createAdjust(AdjustCreateDTO dto, Long applicantId) {
        // 1. 校验调整类型
        AdjustType adjustType = AdjustType.fromCode(dto.getAdjustType());
        if (adjustType == null) {
            throw new ServiceException("非法调整类型：{}", dto.getAdjustType());
        }
        String scope = StringUtils.isBlank(dto.getAdjustScope()) ? SCOPE_DETAIL : dto.getAdjustScope();
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

        // 2. 构建调整单
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
        adjust.setDeltaAmount(dto.getDeltaAmount());
        adjust.setTargetDeptId(dto.getTargetDeptId());
        adjust.setReason(dto.getReason());
        adjust.setPayloadJson(dto.getPayloadJson());
        adjust.setStatus(AdjustStatus.SUBMITTED);
        adjust.setApplicantId(applicantId);

        // 合同级调整前端可能未传员工/部门（合同聚合行无此信息），从该合同首条 ACTIVE 事实回填
        if (SCOPE_CONTRACT.equals(scope)
            && (dto.getDeptId() == null || dto.getDeptId() <= 0)) {
            List<PerformanceFact> facts = factMapper.selectActiveFactsByContractNo(
                dto.getPeriod(), dto.getFactType(), dto.getContractNo());
            if (facts == null || facts.isEmpty()) {
                throw new ServiceException("合同下未找到有效业绩事实，无法发起调整：{}", dto.getContractNo());
            }
            adjust.setDeptId(facts.get(0).getDeptId());
            if (adjust.getEmployeeId() == null) {
                adjust.setEmployeeId(facts.get(0).getEmployeeId());
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
            executeContractAmountAdjust(adjust, operatorId);
        } else {
            switch (adjust.getAdjustType()) {
                case AMOUNT -> executeAmountAdjust(adjust, operatorId);
                case VOID -> executeVoidAdjust(adjust, operatorId);
                case TRANSFER -> executeTransferAdjust(adjust, operatorId);
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
     * 构建合同级分摊后的新事实：按分到的业绩调整额（performance 口径）反推原始金额。
     */
    private PerformanceFact buildContractAdjustedFact(PerformanceFact oldFact, BigDecimal deltaPerformance,
                                                      Long adjustId) {
        BigDecimal newPerformance = MoneyUtil.round2(
            oldFact.getPerformanceAmount().add(deltaPerformance));
        BigDecimal ratio = oldFact.getShareRatio().multiply(oldFact.getConversionRate());
        BigDecimal newOrigin;
        if (MoneyUtil.isZero(ratio)) {
            // 系数为 0 的异常数据：无比例可反推，保持原原始金额（仅业绩金额变化）
            log.warn("[调整单] 原事实分摊×折算系数为 0，原始金额保持不变：factId={}", oldFact.getId());
            newOrigin = oldFact.getOriginAmount();
        } else {
            newOrigin = MoneyUtil.round2(newPerformance.divide(ratio, 2, RoundingMode.HALF_UP));
        }

        PerformanceFact newFact = copyFactBase(oldFact);
        newFact.setOriginAmount(newOrigin);
        newFact.setPerformanceAmount(newPerformance);
        newFact.setAdjustId(adjustId);
        return newFact;
    }

    /**
     * 执行明细级金额调整：旧事实冲销 + 新事实生成（原始金额 += delta，业绩金额按原系数重算）。
     */
    private void executeAmountAdjust(PerformanceAdjust adjust, Long operatorId) {
        PerformanceFact oldFact = getActiveFact(adjust);
        if (adjust.getDeltaAmount() == null) {
            throw new ServiceException("金额调整缺少调整金额：adjustId={}", adjust.getId());
        }

        BigDecimal newOrigin = MoneyUtil.round2(oldFact.getOriginAmount().add(adjust.getDeltaAmount()));
        BigDecimal newPerformance = conversionEngine.calculate(
            newOrigin, oldFact.getShareRatio(), oldFact.getConversionRate());

        PerformanceFact newFact = copyFactBase(oldFact);
        newFact.setOriginAmount(newOrigin);
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
        newFact.setConversionRate(oldFact.getConversionRate());
        newFact.setOriginAmount(oldFact.getOriginAmount());
        newFact.setPerformanceAmount(oldFact.getPerformanceAmount());
        newFact.setEffectiveDate(oldFact.getEffectiveDate() != null
            ? oldFact.getEffectiveDate() : oldFact.getBusinessDate());
        newFact.setFactStatus(FactStatus.ACTIVE);
        newFact.setSource(oldFact.getSource());
        return newFact;
    }
}
