package com.panjia.performance.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.common.util.DeptScopeUtils;
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
import com.panjia.performance.service.FactConversionResolver;
import com.panjia.performance.service.PerformanceAdjustService;
import com.panjia.performance.service.PeriodCloseService;
import com.panjia.performance.service.ReverseService;
import com.panjia.performance.util.MoneyUtil;
import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ApprovalStartCmd;
import com.panjia.contracts.port.ConversionFactorPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.enums.BusinessStatusEnum;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.DeptService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    private final ApprovalPort approvalPort;
    private final PeriodCloseService periodCloseService;
    /** 折算因子公共方法（取比例 / 金额乘算的唯一入口） */
    private final ConversionFactorPort conversionFactorPort;
    /** 业绩域自有标识 → bizType 的解析（factId / 合同号反查） */
    private final FactConversionResolver factConversionResolver;
    /** 部门子树解析（登录用户数据权限范围） */
    private final DeptService deptService;

    @Override
    public PageResult<PerformanceAdjust> listAdjusts(AdjustQuery query, PageQuery pageQuery) {
        // §3.6 数据权限：所有登录用户仅本部门（含下级）。未传 deptId 强制本部门，越权传他部门直接拒绝
        if (query != null) {
            query.setDeptId(DeptScopeUtils.enforceSelfDeptScope(query.getDeptId(), deptService::selectDeptAndChildById, "业绩调整"));
        }
        LambdaQueryWrapper<PerformanceAdjust> wrapper = buildQueryWrapper(query);
        wrapper.orderByDesc(PerformanceAdjust::getCreateTime);

        Page<PerformanceAdjust> page = adjustMapper.selectPage(pageQuery.build(), wrapper);
        List<PerformanceAdjust> records = page.getRecords();
        // 批量回填员工姓名 / 部门名称（含目标部门），避免列表显示裸 ID
        fillDisplayNames(records);
        // 批量回填折算后金额
        fillConvertedAmounts(records);
        return PageResult.build(records, page.getTotal());
    }

    /**
     * 批量回填展示名称：员工姓名（pj_people_employee）、原部门名、目标部门名（sys_dept）。
     * 空集合安全，两次 IN 查询无 N+1。
     * 注意：合同级调整不回填员工姓名（一个合同下可能有多个人）。
     */
    private void fillDisplayNames(List<PerformanceAdjust> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        // 合同级调整不回填员工
        Set<Long> employeeIds = records.stream()
            .filter(r -> r.getEmployeeId() != null && !SCOPE_CONTRACT.equals(r.getAdjustScope()))
            .map(PerformanceAdjust::getEmployeeId)
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
            // 合同级：清空员工信息，避免误导
            if (SCOPE_CONTRACT.equals(r.getAdjustScope())) {
                r.setEmployeeId(null);
                r.setEmployeeName(null);
            } else if (r.getEmployeeId() != null) {
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

    /**
     * 批量回填折算后金额：按 factId 批量查折算因子，合同级（无 factId）按合同号查。
     * 空集合安全。
     */
    private void fillConvertedAmounts(List<PerformanceAdjust> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        // 收集有 factId 的记录
        Set<Long> factIds = records.stream()
            .filter(r -> r.getFactId() != null)
            .map(PerformanceAdjust::getFactId)
            .collect(Collectors.toSet());

        Map<Long, BigDecimal> factorMap = factConversionResolver.factorByFactIds(factIds);

        for (PerformanceAdjust r : records) {
            // 这里取的是「有没有解析到」而非取值兜底，故用 Map.get 判存在，再逐级回退
            BigDecimal factor = r.getFactId() != null ? factorMap.get(r.getFactId()) : null;
            if (factor == null && r.getContractNo() != null && r.getPeriod() != null) {
                String factType = r.getFactType() != null ? r.getFactType() : FACT_TYPE_EXPECT;
                factor = factConversionResolver.factorByContract(r.getPeriod(), r.getContractNo(), factType);
            }
            if (factor == null) {
                factor = BigDecimal.ONE;
            }
            r.setConvertedTargetAmount(conversionFactorPort.convert(r.getTargetAmount(), factor));
            r.setConvertedOriginalAmount(conversionFactorPort.convert(r.getOriginalAmount(), factor));
        }
    }

    @Override
    public PerformanceAdjust getAdjust(Long id) {
        PerformanceAdjust adjust = adjustMapper.selectById(id);
        if (adjust != null) {
            // 状态自愈：工作流已终态但调整单还是 SUBMITTED 时自动对齐
            adjust = syncStatusWithWorkflow(adjust);
            fillDisplayNames(List.of(adjust));
            fillConvertedAmounts(List.of(adjust));
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
            BigDecimal delta = deltaOf(adjust);
            boolean isExpectType = "PERF_EXPECT".equals(factType);

            // 调整已执行（EXECUTED）后，SQL 查到的是新 ACTIVE 事实，amount 已是调整后金额。
            // 此时需反转语义：把 SQL 的 amount 作为 afterAmount（调整后），
            // 再反推原始金额 amount = afterAmount - delta，避免在调整后金额上再叠加 delta 重复计算。
            boolean alreadyExecuted = adjust.getStatus() == AdjustStatus.EXECUTED;
            if (alreadyExecuted) {
                for (AdjustFactDetailDTO d : details) {
                    BigDecimal currentAmount = d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO;
                    // 反推原始金额：原始 = 当前 - 变动；但此时 delta 尚未计算，先暂存当前 amount
                    d.setAfterAmount(currentAmount);
                }
                // 已执行场景：原始 amount 需在 delta 计算后反推，先重置为 null 标记
                for (AdjustFactDetailDTO d : details) {
                    d.setAmount(null);
                }
            }

            if (isContractScope) {
                // 合同级：按金额占比分摊 delta（与执行逻辑共用同一分摊方法）
                // 分摊基准：未执行用当前 amount，已执行用 afterAmount（即调整后金额）反推
                List<BigDecimal> amounts = details.stream()
                    .map(d -> {
                        BigDecimal a = d.getAmount();
                        if (a == null && alreadyExecuted) {
                            // 已执行场景：用 afterAmount 作为分摊基准（与执行时用原始 amount 分摊的口径一致）
                            a = d.getAfterAmount() != null ? d.getAfterAmount() : BigDecimal.ZERO;
                        }
                        return a != null ? a : BigDecimal.ZERO;
                    })
                    .toList();
                BigDecimal[] parts = allocateByAmount(amounts, delta);
                for (int i = 0; i < details.size(); i++) {
                    AdjustFactDetailDTO d = details.get(i);
                    BigDecimal afterAmt = d.getAfterAmount() != null ? d.getAfterAmount() : BigDecimal.ZERO;
                    d.setDeltaAmount(parts[i]);
                    if (alreadyExecuted) {
                        // 已执行：amount = afterAmount - delta（反推原始金额）
                        d.setAmount(MoneyUtil.round2(afterAmt.subtract(parts[i])));
                    } else {
                        // 未执行：afterAmount = amount + delta（推算调整后）
                        BigDecimal amt = d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO;
                        d.setAfterAmount(MoneyUtil.round2(amt.add(parts[i])));
                    }
                    d.setTarget(true);
                }
            } else {
                // 明细级：只标记目标行
                for (AdjustFactDetailDTO d : details) {
                    if (d.getFactId() != null && d.getFactId().equals(factId)) {
                        d.setDeltaAmount(delta);
                        if (alreadyExecuted) {
                            BigDecimal afterAmt = d.getAfterAmount() != null ? d.getAfterAmount() : BigDecimal.ZERO;
                            d.setAmount(MoneyUtil.round2(afterAmt.subtract(delta)));
                        } else {
                            BigDecimal amt = d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO;
                            d.setAfterAmount(MoneyUtil.round2(amt.add(delta)));
                        }
                        d.setTarget(true);
                    } else {
                        d.setDeltaAmount(BigDecimal.ZERO);
                        if (alreadyExecuted) {
                            // 非目标行：amount 和 afterAmount 相同
                            BigDecimal afterAmt = d.getAfterAmount() != null ? d.getAfterAmount() : BigDecimal.ZERO;
                            d.setAmount(afterAmt);
                        } else {
                            BigDecimal amt = d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO;
                            d.setAfterAmount(amt);
                        }
                        d.setTarget(false);
                    }
                }
            }
            // 补充折算后金额：按 factId 批量取折算因子
            Set<Long> detailFactIds = details.stream()
                .map(AdjustFactDetailDTO::getFactId)
                .filter(f -> f != null)
                .collect(Collectors.toSet());
            Map<Long, BigDecimal> detailFactorMap = factConversionResolver.factorByFactIds(detailFactIds);
            for (AdjustFactDetailDTO d : details) {
                BigDecimal factor = conversionFactorPort.factorOf(detailFactorMap, d.getFactId());
                d.setConvertedAmount(conversionFactorPort.convert(d.getAmount(), factor));
                d.setConvertedAfterAmount(conversionFactorPort.convert(d.getAfterAmount(), factor));
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
            // 设置目标总金额（即调整单的 targetAmount）
            if (adjust.getTargetAmount() != null) {
                dto.setTargetAmount(adjust.getTargetAmount());
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

        // 1.5 合同存在已作废明细时禁止调整（作废为合同级操作，口径一致：先恢复合同业绩再调整）
        if (SCOPE_CONTRACT.equals(scope)) {
            if (!factMapper.selectVoidedFactsByContractNo(dto.getPeriod(), dto.getFactType(), dto.getContractNo()).isEmpty()) {
                throw new ServiceException("该合同存在已作废的业绩明细，禁止调整；如需调整请先恢复合同业绩");
            }
        } else if (factMapper.countVoidedSiblingsByFactId(dto.getFactId()) > 0) {
            throw new ServiceException("该合同存在已作废的业绩明细，禁止调整；如需调整请先恢复合同业绩");
        }

        // 2. 计算原始金额 + 验证目标金额
        BigDecimal originalAmt = calculateCurrentAmount(dto, scope);
        // 金额调整：优先取目标金额（用户录入的就是调整后金额）
        BigDecimal targetAmt = dto.getTargetAmount();
        if (adjustType == AdjustType.AMOUNT) {
            if (targetAmt == null) {
                // 兼容旧的 deltaAmount 传参
                if (dto.getDeltaAmount() != null) {
                    targetAmt = originalAmt.add(dto.getDeltaAmount());
                } else {
                    throw new ServiceException("金额调整缺少目标金额");
                }
            }
            dto.setTargetAmount(targetAmt);
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
        adjust.setTargetAmount(targetAmt);
        adjust.setTargetDeptId(dto.getTargetDeptId());
        adjust.setReason(dto.getReason());
        adjust.setPayloadJson(dto.getPayloadJson());
        adjust.setStatus(AdjustStatus.SUBMITTED);
        adjust.setApplicantId(applicantId);
        adjust.setOriginalAmount(originalAmt);

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

        // 3. 发起审批流程（bizId=调整单ID），失败则整体回滚
        ApprovalStartCmd cmd = buildStartCmd(adjust);

        boolean started;
        try {
            started = approvalPort.startAndCompleteFirst(BizType.PERF_ADJUST, adjust.getId(), cmd);
        } catch (Exception e) {
            log.error("[调整单] 审批流程发起异常：adjustId={}", adjust.getId(), e);
            throw new ServiceException("业绩调整审批流程发起失败：{}", e.getMessage());
        }
        if (!started) {
            throw new ServiceException("业绩调整审批流程发起失败");
        }

        // 4. 回填流程实例 ID
        try {
            Long instanceId = approvalPort.instanceId(BizType.PERF_ADJUST, adjust.getId());
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
                // 审批通过 → 先置 APPROVED（补全审批人/时间留痕），再执行调整
                // executeAdjust 内部 checkTransition 走 APPROVED → EXECUTED 路径，
                // 杜绝手动 execute 接口从 SUBMITTED 直接跳 EXECUTED 绕过审批。
                if (adjust.getStatus() == AdjustStatus.EXECUTED) {
                    log.info("[调整单工作流] 已执行，幂等忽略 finish 回调：adjustId={}", adjustId);
                    return;
                }
                if (adjust.getStatus() != AdjustStatus.SUBMITTED && adjust.getStatus() != AdjustStatus.APPROVED) {
                    log.info("[调整单工作流] 非提交/审批通过态，忽略 finish 回调：adjustId={}, current={}",
                        adjustId, adjust.getStatus());
                    return;
                }
                adjust.setStatus(AdjustStatus.APPROVED);
                adjust.setApproverId(handlerId);
                adjust.setApproveTime(LocalDateTime.now());
                adjustMapper.updateById(adjust);
                log.info("[调整单工作流] 审批通过，置 APPROVED 后执行调整：adjustId={}, handler={}, message={}",
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
                approvalPort.cancel(BizType.PERF_ADJUST, id);
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
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markCallbackFailure(Long adjustId, String errorSummary) {
        if (adjustId == null) {
            return;
        }
        PerformanceAdjust adjust = adjustMapper.selectById(adjustId);
        if (adjust == null) {
            log.warn("[调整单] 标记回调失败时调整单不存在：adjustId={}", adjustId);
            return;
        }
        // 追加失败摘要到 reason 字段（带时间戳 + [回调失败] 前缀，便于运维识别）
        String truncated = errorSummary == null ? "未知错误" : errorSummary;
        if (truncated.length() > 200) {
            truncated = truncated.substring(0, 200) + "...";
        }
        String failureMark = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
            + " 回调失败] " + truncated;
        String existingReason = adjust.getReason();
        String newReason = StringUtils.isBlank(existingReason)
            ? failureMark
            : existingReason + " | " + failureMark;
        // 控制总长度，避免 reason 字段撑爆（保留最近的失败上下文）
        if (newReason.length() > 500) {
            newReason = newReason.substring(newReason.length() - 500);
        }
        adjust.setReason(newReason);
        adjustMapper.updateById(adjust);
        log.warn("[调整单] 已追加回调失败摘要：adjustId={}, reason={}", adjustId, failureMark);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void executeAdjust(Long id, Long operatorId) {
        PerformanceAdjust adjust = getAndCheck(id);
        checkTransition(adjust.getStatus(), AdjustStatus.EXECUTED, "调整单");

        // §3.5 封账期间禁止执行调整：原月与目标月（调整生效月）均需未封账
        String originalPeriod = StringUtils.isNotBlank(adjust.getOriginalPeriod())
            ? adjust.getOriginalPeriod() : adjust.getPeriod();
        assertPeriodNotClosed(originalPeriod, "原业绩归属月");
        assertPeriodNotClosed(adjust.getPeriod(), "调整生效月");

        // 根据调整范围 + 类型执行不同逻辑
        if (SCOPE_CONTRACT.equals(adjust.getAdjustScope())) {
            // 合同级仅 AMOUNT：原月=调整月走金额调整；跨月走业绩冲销（§4.6）
            // originalPeriod 已在封账校验前解析
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
     * 构建审批启动命令（业务编码/标题 + 流程变量），供适配器转译为引擎原生 StartProcessDTO + bizExt。
     * <p>合同级调整以合同号作为主标识；明细级调整（无合同号）以员工姓名作为主标识。
     */
    private ApprovalStartCmd buildStartCmd(PerformanceAdjust adjust) {
        // 合同级有合同号 → 用合同号；否则用员工姓名作为主标识
        String subject;
        if (StringUtils.isNotBlank(adjust.getContractNo())) {
            subject = "合同" + adjust.getContractNo();
        } else {
            subject = "员工" + resolveEmployeeName(adjust.getEmployeeId());
        }
        ApprovalStartCmd cmd = ApprovalStartCmd.of(
            text(adjust.getAdjustNo()),
            "业绩调整｜" + subject
                + "｜账期" + text(adjust.getPeriod())
                + "｜类型" + text(adjust.getAdjustType())
                + "｜目标金额" + text(adjust.getTargetAmount())
                + "｜单号" + text(adjust.getAdjustNo()));
        Map<String, Object> variables = new HashMap<>(2);
        // 后端发起无登录用户上下文，忽略权限
        variables.put("ignore", true);
        cmd.setVariables(variables);
        return cmd;
    }

    /**
     * 查询员工姓名，查不到则回退为工号。
     */
    private String resolveEmployeeName(Long employeeId) {
        if (employeeId == null) {
            return "";
        }
        List<Map<String, Object>> rows = adjustMapper.employeeNames(List.of(employeeId));
        if (rows.isEmpty()) {
            return String.valueOf(employeeId);
        }
        return String.valueOf(rows.get(0).get("employeeName"));
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
        // 门店/组别筛选：含下级组别（与业绩查询/业绩明细的部门子树口径一致）。
        // 调整单除原部门外，调拨（TRANSFER）的目标部门命中也视为相关，便于按门店追溯去向。
        if (query.getDeptId() != null) {
            Long deptId = query.getDeptId();
            String subtree = "dept_id = {0} OR dept_id IN (SELECT sd.dept_id FROM sys_dept sd"
                + " WHERE sd.ancestors LIKE CONCAT('%', {0}, '%'))";
            wrapper.and(w -> w.apply(subtree, deptId)
                .or().apply(
                    "target_dept_id = {0} OR target_dept_id IN (SELECT sd.dept_id FROM sys_dept sd"
                        + " WHERE sd.ancestors LIKE CONCAT('%', {0}, '%'))",
                    deptId));
        }
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
     * 保证 Σ新业绩 = targetAmount 精确成立。
     */
    private void executeContractAmountAdjust(PerformanceAdjust adjust, Long operatorId) {
        if (adjust.getTargetAmount() == null) {
            throw new ServiceException("合同级金额调整缺少目标金额：adjustId={}", adjust.getId());
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

        // 关键：基于执行时的当前合计计算 delta，保证最终合计 = targetAmount
        BigDecimal deltaTotal = MoneyUtil.round2(adjust.getTargetAmount().subtract(total));
        List<BigDecimal> amounts = facts.stream()
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .toList();
        BigDecimal[] parts = allocateByAmount(amounts, deltaTotal);

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
        if (adjust.getTargetAmount() == null) {
            throw new ServiceException("合同级跨月调整缺少目标金额：adjustId={}", adjust.getId());
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
        // 关键：基于执行时原月合计计算 delta，保证调整月净额 = targetAmount - 原月合计
        BigDecimal deltaTotal = MoneyUtil.round2(adjust.getTargetAmount().subtract(total));
        List<BigDecimal> amounts = facts.stream()
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .toList();
        BigDecimal[] parts = allocateByAmount(amounts, deltaTotal);

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
     * 关键：delta = targetAmount - 执行时原事实金额，保证调整后两期合计 = targetAmount。
     */
    private void executeDetailCrossMonthAmountAdjust(PerformanceAdjust adjust, PerformanceFact oldFact,
                                                     Long operatorId) {
        if (adjust.getTargetAmount() == null) {
            throw new ServiceException("金额调整缺少目标金额：adjustId={}", adjust.getId());
        }
        BigDecimal currentAmt = oldFact.getPerformanceAmount() == null ? BigDecimal.ZERO : oldFact.getPerformanceAmount();
        BigDecimal performanceDelta = MoneyUtil.round2(adjust.getTargetAmount().subtract(currentAmt));
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
     * 执行明细级金额调整：旧事实冲销 + 新事实生成（performance_amount = targetAmount）。
     */
    private void executeAmountAdjust(PerformanceAdjust adjust, Long operatorId) {
        PerformanceFact oldFact = getActiveFact(adjust);
        if (adjust.getTargetAmount() == null) {
            throw new ServiceException("金额调整缺少目标金额：adjustId={}", adjust.getId());
        }

        BigDecimal newPerformance = MoneyUtil.round2(adjust.getTargetAmount());

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
     * 计算调整变动额：targetAmount - originalAmount。
     * 调整单表存的是目标金额，变动额通过此方法推导。
     */
    private BigDecimal deltaOf(PerformanceAdjust adjust) {
        BigDecimal target = adjust.getTargetAmount() != null ? adjust.getTargetAmount() : BigDecimal.ZERO;
        BigDecimal origin = adjust.getOriginalAmount() != null ? adjust.getOriginalAmount() : BigDecimal.ZERO;
        return target.subtract(origin);
    }

    /**
     * 按金额占比分摊总变动额，返回各条分摊后的变动额数组。
     * <p>
     * 保证：Σparts = deltaTotal 精确成立（尾差补到绝对值最大的一条）。
     * 使用 8 位中间精度 + round2 输出，与执行逻辑完全一致。
     *
     * @param amounts    各条金额（按占比分摊的基准）
     * @param deltaTotal 总变动额
     * @return 各条分摊后的变动额数组，长度与 amounts 一致
     */
    private BigDecimal[] allocateByAmount(List<BigDecimal> amounts, BigDecimal deltaTotal) {
        BigDecimal total = amounts.stream()
            .map(a -> a == null ? BigDecimal.ZERO : a)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal[] parts = new BigDecimal[amounts.size()];
        if (total.signum() == 0) {
            // 总额为 0：平摊，尾差补到第一条
            BigDecimal even = amounts.isEmpty() ? BigDecimal.ZERO
                : MoneyUtil.round2(deltaTotal.divide(BigDecimal.valueOf(amounts.size()), 8, RoundingMode.HALF_UP));
            BigDecimal allocated = BigDecimal.ZERO;
            for (int i = 0; i < amounts.size(); i++) {
                parts[i] = even;
                allocated = allocated.add(even);
            }
            if (amounts.size() > 0) {
                parts[0] = MoneyUtil.round2(parts[0].add(deltaTotal.subtract(allocated)));
            }
            return parts;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        int largestIdx = 0;
        BigDecimal largestAbs = BigDecimal.ZERO;
        for (int i = 0; i < amounts.size(); i++) {
            BigDecimal base = amounts.get(i) == null ? BigDecimal.ZERO : amounts.get(i);
            parts[i] = MoneyUtil.round2(
                deltaTotal.multiply(base).divide(total, 8, RoundingMode.HALF_UP));
            allocated = allocated.add(parts[i]);
            if (base.abs().compareTo(largestAbs) > 0) {
                largestAbs = base.abs();
                largestIdx = i;
            }
        }
        // 尾差补到绝对值最大的行
        parts[largestIdx] = MoneyUtil.round2(parts[largestIdx].add(deltaTotal.subtract(allocated)));
        return parts;
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
        // 注意：batch_id 不继承——调整生成的事实不归属于任何导入批次，
        // 避免后续批次 supersede / 重归一化时被误冲销
        newFact.setBatchId(null);
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

    /**
     * §3.5 封账校验：指定期间已 CLOSED 时抛业务异常，禁止执行调整。
     *
     * @param period 期间（YYYY-MM）
     * @param label   期间用途描述（用于异常消息）
     */
    private void assertPeriodNotClosed(String period, String label) {
        if (StringUtils.isBlank(period)) {
            return;
        }
        if (periodCloseService.isClosed(period)) {
            throw new ServiceException("期间已封账，禁止执行调整：" + label + "=" + period);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PerformanceAdjust syncStatusWithWorkflow(PerformanceAdjust adjust) {
        // 只有 SUBMITTED 状态的单据才可能出现"工作流已终态但业务未更新"的卡住
        if (adjust == null || adjust.getStatus() != AdjustStatus.SUBMITTED) {
            return adjust;
        }
        if (StringUtils.isBlank(adjust.getProcessInstanceId())) {
            return adjust;
        }
        String wfStatus;
        try {
            wfStatus = approvalPort.businessStatus(BizType.PERF_ADJUST, adjust.getId());
        } catch (Exception e) {
            log.warn("[调整单状态自愈] 查询工作流状态失败，跳过：adjustId={}", adjust.getId(), e);
            return adjust;
        }
        if (StringUtils.isBlank(wfStatus)) {
            return adjust;
        }
        // 工作流还是运行中状态（waiting / draft），无需修复
        if (BusinessStatusEnum.WAITING.getStatus().equals(wfStatus)
            || BusinessStatusEnum.DRAFT.getStatus().equals(wfStatus)) {
            return adjust;
        }

        // 工作流已进入终态但调整单还是 SUBMITTED → 按工作流状态对齐
        log.info("[调整单状态自愈] 发现状态不一致，开始修复：adjustId={}, adjustStatus={}, wfStatus={}",
            adjust.getId(), adjust.getStatus(), wfStatus);

        switch (wfStatus) {
            case "cancel" -> {
                adjust.setStatus(AdjustStatus.CANCELLED);
                adjust.setOperatorId(null);
                adjustMapper.updateById(adjust);
                log.info("[调整单状态自愈] 已修复为 CANCELLED：adjustId={}", adjust.getId());
            }
            case "finish" -> {
                // 审批通过但未执行 → 直接执行调整（幂等）
                log.info("[调整单状态自愈] 工作流已 finish，补执行调整：adjustId={}", adjust.getId());
                adjust.setStatus(AdjustStatus.APPROVED);
                adjustMapper.updateById(adjust);
                try {
                    executeAdjust(adjust.getId(), null);
                    adjust = adjustMapper.selectById(adjust.getId());
                } catch (Exception e) {
                    log.error("[调整单状态自愈] 补执行调整失败：adjustId={}", adjust.getId(), e);
                    markCallbackFailure(adjust.getId(), "状态自愈补执行失败: " + e.getMessage());
                }
            }
            case "back" -> {
                adjust.setStatus(AdjustStatus.REJECTED);
                adjustMapper.updateById(adjust);
                log.info("[调整单状态自愈] 已修复为 REJECTED（驳回）：adjustId={}", adjust.getId());
            }
            case "invalid", "termination" -> {
                adjust.setStatus(AdjustStatus.REJECTED);
                adjustMapper.updateById(adjust);
                log.info("[调整单状态自愈] 已修复为 REJECTED（作废/终止）：adjustId={}", adjust.getId());
            }
            default -> log.info("[调整单状态自愈] 未知工作流状态，不处理：adjustId={}, wfStatus={}", adjust.getId(), wfStatus);
        }
        return adjust;
    }
}
