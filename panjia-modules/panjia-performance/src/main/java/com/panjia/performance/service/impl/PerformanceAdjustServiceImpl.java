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
import com.panjia.performance.domain.PerformanceSource;
import com.panjia.performance.domain.bo.AdjustDetailTargetBo;
import com.panjia.performance.domain.bo.AdjustDeductionBo;
import com.panjia.performance.domain.bo.AddMemberPayload;
import com.panjia.performance.domain.bo.PerformanceAdjustCreateBo;
import com.panjia.performance.domain.vo.AdjustDetailVo;
import com.panjia.performance.domain.vo.AdjustFactDetailVo;
import com.panjia.performance.domain.bo.PerformanceAdjustBo;
import com.panjia.performance.mapper.PerformanceAdjustMapper;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.service.FactConversionResolver;
import com.panjia.performance.service.IPerformanceAdjustService;
import com.panjia.performance.service.IPeriodCloseService;
import com.panjia.performance.service.ReverseService;
import com.panjia.performance.util.MoneyUtil;
import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ApprovalStartCmd;
import com.panjia.contracts.port.CommissionGatePort;
import com.panjia.contracts.port.ConversionFactorPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.enums.BusinessStatusEnum;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.DeptService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 *   <li>CONTRACT（合同级）：金额调整（按各明细 performance_amount 占比分摊，
 *       尾差补到金额最大的一条，逐条 supersede）；
 *       或增加角色人 ADD_MEMBER（手工多一人分业绩，合同总额不变，2026-09-28）；</li>
 *   <li>DETAIL（明细级）：金额调整 / 业绩冲销 / 部门划转，作用于单条事实。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceAdjustServiceImpl implements IPerformanceAdjustService {

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
    private final IPeriodCloseService periodCloseService;
    /** 折算因子公共方法（取比例 / 金额乘算的唯一入口） */
    private final ConversionFactorPort conversionFactorPort;
    /** 业绩域自有标识 → bizType 的解析（factId / 合同号反查） */
    private final FactConversionResolver factConversionResolver;
    /** 部门子树解析（登录用户数据权限范围） */
    private final DeptService deptService;
    /**
     * 结佣闸门端口（发起/执行新签调整前校验结佣是否已审批锁定）。ObjectProvider 惰性取用：
     * 实现 bean 在 panjia-commission（performance 不反向依赖 commission），运行期由 Spring 装配，
     * 端口实现缺失时回退跳过校验，保证本模块上下文可独立启动。
     */
    private final ObjectProvider<CommissionGatePort> commissionGatePortProvider;

    @Override
    public PageResult<PerformanceAdjust> listAdjusts(PerformanceAdjustBo query, PageQuery pageQuery) {
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
        // ADD_MEMBER 回填调整后合同总额（混合金额调整时与原总额不等）
        fillAddMemberAfterTotal(records);
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
        // 合同级调整不回填员工（ADD_MEMBER 例外：employeeId=新角色人，列表需显示新人姓名）
        Set<Long> employeeIds = records.stream()
            .filter(r -> r.getEmployeeId() != null
                && (!SCOPE_CONTRACT.equals(r.getAdjustScope()) || r.getAdjustType() == AdjustType.ADD_MEMBER))
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
            // 合同级：清空员工信息，避免误导（ADD_MEMBER 例外：显示新角色人）
            if (SCOPE_CONTRACT.equals(r.getAdjustScope()) && r.getAdjustType() != AdjustType.ADD_MEMBER) {
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

    /**
     * 回填 ADD_MEMBER 单据的「调整后合同业绩合计」：解析 payload.afterTotal；
     * 旧快照无该字段时回退原合同总额（2026-09-29 前单据均为总额不变）。
     */
    private void fillAddMemberAfterTotal(List<PerformanceAdjust> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (PerformanceAdjust r : records) {
            if (r.getAdjustType() != AdjustType.ADD_MEMBER) {
                continue;
            }
            BigDecimal afterTotal = null;
            AddMemberPayload payload = parseAddMemberPayload(r.getPayloadJson());
            if (payload != null) {
                afterTotal = payload.getAfterTotal();
            }
            r.setAfterTotalAmount(afterTotal != null ? MoneyUtil.round2(afterTotal)
                : (r.getOriginalAmount() != null ? MoneyUtil.round2(r.getOriginalAmount()) : null));
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
            fillAddMemberAfterTotal(List.of(adjust));
        }
        return adjust;
    }

    @Override
    public AdjustDetailVo getAdjustDetail(Long id) {
        PerformanceAdjust adjust = getAdjust(id);
        if (adjust == null) {
            return null;
        }
        AdjustDetailVo dto = new AdjustDetailVo();
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
            List<AdjustFactDetailVo> details =
                factMapper.selectAdjustFactDetails(period, targetContractNo, factType);
            // 增加角色人（ADD_MEMBER）：分摊展示语义与金额调整完全不同，独立处理后直接返回
            if (adjust.getAdjustType() == AdjustType.ADD_MEMBER) {
                applyAddMemberDetail(adjust, dto, details);
                return dto;
            }
            // 计算变动金额
            BigDecimal delta = deltaOf(adjust);
            boolean isExpectType = "PERF_EXPECT".equals(factType);

            // 调整已执行（EXECUTED）后，SQL 查到的是新 ACTIVE 事实，amount 已是调整后金额。
            // 此时需反转语义：把 SQL 的 amount 作为 afterAmount（调整后），
            // 再反推原始金额 amount = afterAmount - delta，避免在调整后金额上再叠加 delta 重复计算。
            boolean alreadyExecuted = adjust.getStatus() == AdjustStatus.EXECUTED;
            if (alreadyExecuted) {
                for (AdjustFactDetailVo d : details) {
                    BigDecimal currentAmount = d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO;
                    // 反推原始金额：原始 = 当前 - 变动；但此时 delta 尚未计算，先暂存当前 amount
                    d.setAfterAmount(currentAmount);
                }
                // 已执行场景：原始 amount 需在 delta 计算后反推，先重置为 null 标记
                for (AdjustFactDetailVo d : details) {
                    d.setAmount(null);
                }
            }

            if (isContractScope) {
                // 合同级：优先按 payload 预演快照逐行精确回填（指定值模式，2026-09-28 可编辑表格）；
                // 无快照时按金额占比分摊 delta（与执行逻辑共用同一分摊方法）
                Map<Long, BigDecimal> deltaByFact = contractAdjustDeltaByFact(adjust);
                if (deltaByFact != null) {
                    for (AdjustFactDetailVo d : details) {
                        BigDecimal rowDelta = d.getFactId() != null
                            ? deltaByFact.getOrDefault(d.getFactId(), BigDecimal.ZERO) : BigDecimal.ZERO;
                        BigDecimal afterAmt = d.getAfterAmount() != null ? d.getAfterAmount() : BigDecimal.ZERO;
                        d.setDeltaAmount(rowDelta);
                        if (alreadyExecuted) {
                            // 已执行：amount = afterAmount - delta（反推原始金额）
                            d.setAmount(MoneyUtil.round2(afterAmt.subtract(rowDelta)));
                        } else {
                            // 未执行：afterAmount = amount + delta（推算调整后）
                            BigDecimal amt = d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO;
                            d.setAfterAmount(MoneyUtil.round2(amt.add(rowDelta)));
                        }
                        d.setTarget(rowDelta.signum() != 0);
                    }
                } else {
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
                BigDecimal[] parts = MoneyUtil.allocateByAmount(amounts, delta);
                for (int i = 0; i < details.size(); i++) {
                    AdjustFactDetailVo d = details.get(i);
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
                }
            } else {
                // 明细级：只标记目标行
                for (AdjustFactDetailVo d : details) {
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
                .map(AdjustFactDetailVo::getFactId)
                .filter(f -> f != null)
                .collect(Collectors.toSet());
            Map<Long, BigDecimal> detailFactorMap = factConversionResolver.factorByFactIds(detailFactIds);
            for (AdjustFactDetailVo d : details) {
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
    public PerformanceAdjust createAdjust(PerformanceAdjustCreateBo dto, Long applicantId) {
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
            // 合同级支持金额调整（按占比分摊）与增加角色人（2026-09-28，手工多一人分业绩、合同总额不变）
            if (adjustType != AdjustType.AMOUNT && adjustType != AdjustType.ADD_MEMBER) {
                throw new ServiceException("合同级调整仅支持金额调整或增加角色人");
            }
            if (StringUtils.isBlank(dto.getContractNo()) || StringUtils.isBlank(dto.getFactType())) {
                throw new ServiceException("合同级调整缺少合同号或事实口径");
            }
        } else if (dto.getFactId() == null) {
            throw new ServiceException("明细级调整缺少关联业绩事实");
        } else {
            // 明细级：校验该事实所属合同的结佣是否已审批锁定
            PerformanceFact fact = factMapper.selectById(dto.getFactId());
            if (fact != null && StringUtils.isNotBlank(fact.getContractNo())) {
                assertCommissionNotLocked(fact.getPeriod(), fact.getContractNo());
            }
        }

        // 1.5 合同存在已作废明细时禁止调整（作废为合同级操作，口径一致：先恢复合同业绩再调整）
        if (SCOPE_CONTRACT.equals(scope)) {
            if (!factMapper.selectVoidedFactsByContractNo(dto.getPeriod(), dto.getFactType(), dto.getContractNo()).isEmpty()) {
                throw new ServiceException("该合同存在已作废的业绩明细，禁止调整；如需调整请先恢复合同业绩");
            }
        } else if (factMapper.countVoidedSiblingsByFactId(dto.getFactId()) > 0) {
            throw new ServiceException("该合同存在已作废的业绩明细，禁止调整；如需调整请先恢复合同业绩");
        }

        // ==================== 增加角色人（ADD_MEMBER，2026-09-28） ====================
        // 给合同手工多加一个人分业绩：新人金额 X，优先从指定行精确扣除，剩余由未指定行
        // 按业绩占比等比分摊，合同总额不变。校验 + 分摊预演快照见 prepareAddMember
        if (adjustType == AdjustType.ADD_MEMBER) {
            dto.setPayloadJson(prepareAddMember(dto));
        } else if (adjustType == AdjustType.AMOUNT && SCOPE_CONTRACT.equals(scope)
            && dto.getDetailTargets() != null && !dto.getDetailTargets().isEmpty()) {
            // 合同级金额调整指定值模式（可编辑表格，2026-09-28）：校验并快照明细「调整后金额/占比」
            dto.setPayloadJson(prepareContractAmountTargets(dto));
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

        // 2.5 合同级调整：dto.contractNo 是前端业务键（订单号优先、合同号兜底），
        // 落库前归一化为事实表的真实合同号——否则调整单/详情把订单号当合同号展示，
        // 而事实表 contract_no / order_no 两列本就分开存储
        if (SCOPE_CONTRACT.equals(scope)) {
            dto.setContractNo(resolveRealContractNo(dto));
            // 结佣已审批锁定则禁止发起新签调整（保护已审批结佣数据）
            String gatePeriod = StringUtils.isNotBlank(dto.getOriginalPeriod())
                ? dto.getOriginalPeriod() : dto.getPeriod();
            assertCommissionNotLocked(gatePeriod, dto.getContractNo());
        }

        // 2.6 在途互斥：同合同存在审批中（SUBMITTED/APPROVED）调整单时拒绝发起。
        // 调整单 payload 快照 factId，在途期间事实被先执行的单 supersede 后，
        // 后执行单会因指定行失效而卡死；同合同调整必须串行（撤回或等审批完结再发起）。
        assertNoInFlightAdjust(scope, dto.getContractNo(), dto.getPeriod(),
            dto.getOriginalPeriod(), dto.getFactType(), dto.getFactId());

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

    /**
     * 合同级调整的真实合同号归一化：dto.contractNo 为前端业务键（订单号优先、合同号兜底），
     * 取该合同任一 ACTIVE 事实的 contract_no 回填；事实 contract_no 为空或无事实时原样返回业务键。
     * 跨月调整时事实在 originalPeriod（原业绩归属月），与下方员工/部门回填的取数期间一致。
     */
    private String resolveRealContractNo(PerformanceAdjustCreateBo dto) {
        String factLoadPeriod = StringUtils.isNotBlank(dto.getOriginalPeriod())
            ? dto.getOriginalPeriod() : dto.getPeriod();
        List<PerformanceFact> facts = factMapper.selectActiveFactsByContractNo(
            factLoadPeriod, dto.getFactType(), dto.getContractNo());
        return facts.stream()
            .map(PerformanceFact::getContractNo)
            .filter(StringUtils::isNotBlank)
            .findFirst()
            .orElse(dto.getContractNo());
    }

    /**
     * 在途互斥校验：同一合同（期间口径重叠 + 同事实口径）已存在 SUBMITTED/APPROVED 调整单时，
     * 禁止再发起新的调整单。
     * <p>
     * 必要性：调整单 payload 按 factId 快照明细，supersede 后旧事实置 REVERSED、新事实换 ID。
     * 两张在途单无论谁先执行，后执行单的快照 factId 都会失效（加人/合同金额指定值模式直接拒绝执行，
     * 单据永久卡死）。跨月调整同时涉及原月与目标月，故期间按 period/originalPeriod 重叠判定。
     * <p>
     * 明细级发起时 dto 可能不带合同号，则按 factId 本身或该事实所属合同号判定互斥范围。
     *
     * @param scope          调整范围（CONTRACT/DETAIL）
     * @param contractNo     合同号（合同级已归一化为事实表真实合同号；明细级可能为空）
     * @param period         调整生效月
     * @param originalPeriod 原业绩归属月（跨月调整）
     * @param factType       事实口径
     * @param factId         明细级调整关联事实 ID（合同级为空）
     */
    private void assertNoInFlightAdjust(String scope, String contractNo, String period,
                                        String originalPeriod, String factType, Long factId) {
        String originPeriod = StringUtils.isNotBlank(originalPeriod) ? originalPeriod : period;
        LambdaQueryWrapper<PerformanceAdjust> qw = new LambdaQueryWrapper<>();
        qw.eq(PerformanceAdjust::getFactType, factType)
            .in(PerformanceAdjust::getStatus, AdjustStatus.SUBMITTED, AdjustStatus.APPROVED)
            .and(w -> w.eq(PerformanceAdjust::getPeriod, originPeriod)
                .or().eq(PerformanceAdjust::getOriginalPeriod, originPeriod)
                .or().eq(PerformanceAdjust::getPeriod, period)
                .or().eq(PerformanceAdjust::getOriginalPeriod, period));
        if (StringUtils.isNotBlank(contractNo)) {
            qw.eq(PerformanceAdjust::getContractNo, contractNo);
        } else if (factId != null) {
            PerformanceFact ref = factMapper.selectById(factId);
            String refContractNo = ref == null ? null : ref.getContractNo();
            // 同 factId 的在途单，或同一事实上合同号下的在途单，均互斥
            qw.and(w -> w.eq(PerformanceAdjust::getFactId, factId)
                .or(StringUtils.isNotBlank(refContractNo),
                    x -> x.eq(PerformanceAdjust::getContractNo, refContractNo)));
        } else {
            // 无合同键也无 factId 无法判定互斥范围，后续必填校验会拦截，这里不阻断
            return;
        }
        Long count = adjustMapper.selectCount(qw);
        if (count != null && count > 0) {
            log.warn("[调整单] 在途互斥拦截：scope={}, contractNo={}, period={}, originalPeriod={}, factId={}, 在途单数={}",
                scope, contractNo, period, originalPeriod, factId, count);
            throw new ServiceException("该合同存在审批中的业绩调整单，请待其审批完成或撤回后再发起新调整");
        }
    }

    /**
     * 前端预检：该合同是否存在审批中的业绩调整单（SUBMITTED/APPROVED）。
     * <p>与 {@link #assertNoInFlightAdjust} 同口径（期间重叠 + 同口径 + 同合同），
     * 供前端在选完业绩事实后即时禁用提交按钮，避免提交后才被后端拒绝。
     *
     * @param contractNo 合同号
     * @param period     调整生效月
     * @param factType   事实口径
     * @return true=存在在途调整单，前端应禁用提交
     */
    @Override
    public boolean hasInFlightAdjust(String contractNo, String period, String factType) {
        if (StringUtils.isBlank(contractNo) || StringUtils.isBlank(period) || StringUtils.isBlank(factType)) {
            return false;
        }
        LambdaQueryWrapper<PerformanceAdjust> qw = new LambdaQueryWrapper<>();
        qw.eq(PerformanceAdjust::getFactType, factType)
            .in(PerformanceAdjust::getStatus, AdjustStatus.SUBMITTED, AdjustStatus.APPROVED)
            .eq(PerformanceAdjust::getContractNo, contractNo)
            .and(w -> w.eq(PerformanceAdjust::getPeriod, period)
                .or().eq(PerformanceAdjust::getOriginalPeriod, period));
        Long count = adjustMapper.selectCount(qw);
        return count != null && count > 0;
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
    public void withdraw(Long id, Long operatorId) {
        PerformanceAdjust adjust = adjustMapper.selectById(id);
        if (adjust == null) {
            throw new ServiceException("调整单不存在：id={}", id);
        }
        if (adjust.getStatus() != AdjustStatus.SUBMITTED) {
            throw new ServiceException("仅审批中的调整单允许撤回，当前状态：{}",
                adjust.getStatus() == null ? "未知" : adjust.getStatus().getCode());
        }
        if (!Objects.equals(adjust.getApplicantId(), operatorId)) {
            throw new ServiceException("仅调整单发起人本人可撤回");
        }
        // 删除工作流实例（任务/历史/授权一并清理）。失败则整单回滚，不能出现业务已取消但流程仍在审批
        try {
            approvalPort.cancel(BizType.PERF_ADJUST, id);
        } catch (Exception e) {
            log.error("[调整单] 撤回审批流程失败：adjustId={}", id, e);
            throw new ServiceException("撤回审批流程失败：{}", e.getMessage());
        }
        adjust.setStatus(AdjustStatus.CANCELLED);
        adjust.setProcessInstanceId(null);
        adjust.setOperatorId(operatorId);
        adjustMapper.updateById(adjust);
        log.info("[调整单] 申请人撤回：adjustId={}, adjustNo={}, operatorId={}",
            id, adjust.getAdjustNo(), operatorId);
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
        // 结佣已审批锁定则禁止执行新签调整（兜底：防发起后结佣变锁定）
        assertCommissionNotLocked(originalPeriod, adjust.getContractNo());

        // 根据调整范围 + 类型执行不同逻辑
        if (SCOPE_CONTRACT.equals(adjust.getAdjustScope())) {
            // 合同级：ADD_MEMBER 增加角色人（当期生效）；AMOUNT 原月=调整月走金额调整，跨月走业绩冲销（§4.6）
            // originalPeriod 已在封账校验前解析
            if (adjust.getAdjustType() == AdjustType.ADD_MEMBER) {
                executeAddMemberAdjust(adjust, operatorId);
            } else if (originalPeriod.equals(adjust.getPeriod())) {
                executeContractAmountAdjust(adjust, operatorId);
            } else {
                executeContractCrossMonthAdjust(adjust, originalPeriod, operatorId);
            }
        } else {
            PerformanceFact oldFact = getActiveFact(adjust);
            boolean crossMonth = !oldFact.getPeriod().equals(adjust.getPeriod());
            // 明细级仅支持金额调整（AMOUNT）；VOID/TRANSFER 已下线
            if (adjust.getAdjustType() == AdjustType.AMOUNT) {
                if (crossMonth) {
                    executeDetailCrossMonthAmountAdjust(adjust, oldFact, operatorId);
                } else {
                    executeAmountAdjust(adjust, operatorId);
                }
            } else {
                throw new ServiceException("不支持的调整类型：{}", adjust.getAdjustType());
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

    /** 结佣闸门端口（端口实现缺失时返回 null，校验跳过）。 */
    private CommissionGatePort commissionGate() {
        return commissionGatePortProvider.getIfAvailable();
    }

    /**
     * 校验指定合同 + 业绩归属月的结佣是否已审批锁定（LOCKED）。
     * <p>结佣已审批通过并计入工资后，新签事实调整会破坏一致性，必须先作废结佣单再调整。
     * 端口实现缺失（本模块独立启动）或参数缺失时跳过校验。
     */
    private void assertCommissionNotLocked(String period, String contractNo) {
        if (StringUtils.isBlank(period) || StringUtils.isBlank(contractNo)) {
            return;
        }
        CommissionGatePort gate = commissionGate();
        if (gate != null && gate.isCommissionLocked(period, contractNo)) {
            throw new ServiceException("合同 " + contractNo + " " + period
                + " 月结佣已审批通过并锁定，不能发起新签调整；如需调整请先作废结佣单");
        }
    }

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
        // 增加角色人：employeeId=新角色人，审批标题带上新人姓名
        if (adjust.getAdjustType() == AdjustType.ADD_MEMBER) {
            subject = subject + "｜新人" + resolveEmployeeName(adjust.getEmployeeId());
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
    private LambdaQueryWrapper<PerformanceAdjust> buildQueryWrapper(PerformanceAdjustBo query) {
        LambdaQueryWrapper<PerformanceAdjust> wrapper = new LambdaQueryWrapper<>();
        // 排除结佣调整镜像（adjust_no 以 CADJ 开头）：镜像是结佣域登记的内部快照，
        // 仅用于新签明细页还原「原值→调整后值」，不属于新签侧审批单，不应出现在新签调整列表
        wrapper.notLike(PerformanceAdjust::getAdjustNo, "CADJ");
        wrapper.eq(StringUtils.isNotBlank(query.getPeriod()),
            PerformanceAdjust::getPeriod, query.getPeriod());
        wrapper.eq(StringUtils.isNotBlank(query.getAdjustType()),
            PerformanceAdjust::getAdjustType, AdjustType.fromCode(query.getAdjustType()));
        wrapper.eq(StringUtils.isNotBlank(query.getStatus()),
            PerformanceAdjust::getStatus, AdjustStatus.fromCode(query.getStatus()));
        wrapper.eq(query.getEmployeeId() != null,
            PerformanceAdjust::getEmployeeId, query.getEmployeeId());
        // 门店/组别筛选：含下级组别（与业绩查询/业绩明细的部门子树口径一致）。
        if (query.getDeptId() != null) {
            Long deptId = query.getDeptId();
            String subtree = "dept_id = {0} OR dept_id IN (SELECT sd.dept_id FROM sys_dept sd"
                + " WHERE sd.ancestors LIKE CONCAT('%', {0}, '%'))";
            wrapper.apply(subtree, deptId);
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
     * 合同级指定值模式：从 payload 预演快照取每行 delta（factId→delta）。
     * 无 payload / 无 allocations 时返回 null（调用方回退等比分摊）。
     */
    private Map<Long, BigDecimal> contractAdjustDeltaByFact(PerformanceAdjust adjust) {
        if (adjust == null || adjust.getPayloadJson() == null) {
            return null;
        }
        AddMemberPayload payload = parseAddMemberPayload(adjust.getPayloadJson());
        if (payload == null || payload.getAllocations() == null || payload.getAllocations().isEmpty()) {
            return null;
        }
        Map<Long, BigDecimal> deltaByFact = new HashMap<>();
        for (AddMemberPayload.Alloc a : payload.getAllocations()) {
            if (a != null && a.getFactId() != null) {
                deltaByFact.put(a.getFactId(), a.getDelta() == null ? BigDecimal.ZERO : a.getDelta());
            }
        }
        return deltaByFact.isEmpty() ? null : deltaByFact;
    }

    /**
     * 执行合同级金额调整。
     * <p>
     * 指定值模式（payload.detailTargets 非空，2026-09-28 可编辑表格）：按行精确落到指定
     * 「调整后金额/角色占比」，不再等比分摊；执行时完整性校验
     * Σ指定行目标 + 未指定行当前金额 = targetAmount，不满足则拒绝执行。
     * <p>
     * 旧模式（无 detailTargets）：按各明细 performance_amount 占比分摊总调整额，逐条 supersede；
     * 分摊尾差补到业绩金额绝对值最大的一条，保证 Σ新业绩 = targetAmount 精确成立。
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

        AddMemberPayload payload = parseAddMemberPayload(adjust.getPayloadJson());
        if (payload != null && payload.getDetailTargets() != null && !payload.getDetailTargets().isEmpty()) {
            executeContractAmountByTargets(adjust, facts, payload, operatorId);
            return;
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
        BigDecimal[] parts = MoneyUtil.allocateByAmount(amounts, deltaTotal);

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
     * 指定值模式目标行解析：快照 factId 仍是执行时 ACTIVE 行则直接返回；
     * 若该行在单据审批期间已被其他操作冲销（REVERSED，supersede 时员工/角色/业务类型/费项/
     * 业务日期原样继承到后继行，见 {@link #copyFactBase}），则在当前 ACTIVE 集合中按身份键
     * 定位唯一后继行自动续接，并把入参 target 的 factId 改写为后继行 ID。
     * <p>
     * 无法安全续接时抛业务异常提示撤回重发：找不到事实、行仍 ACTIVE 但已不在本合同当期
     * （归属变化）、或匹配出 0 条/多条候选。金额完整性不由本方法保证——调用方的
     * 「Σ指定行目标 + 未指定行现值 = 单据快照总额」校验会继续兜底拦截金额错乱。
     */
    private PerformanceFact resolveTargetFactOrSuccessor(Map<Long, PerformanceFact> activeById,
                                                         AdjustDetailTargetBo target,
                                                         PerformanceAdjust adjust) {
        Long factId = target.getFactId();
        PerformanceFact current = activeById.get(factId);
        if (current != null) {
            return current;
        }
        PerformanceFact snapshot = factMapper.selectById(factId);
        if (snapshot == null) {
            throw new ServiceException("执行失败：指定调整行已不存在（数据可能已被清理），"
                + "请撤回该调整单后按最新明细重新发起：adjustId={}, factId={}", adjust.getId(), factId);
        }
        if (snapshot.getFactStatus() == FactStatus.ACTIVE) {
            // 行仍有效但不在本次加载的期间/合同集合中：归属已被改变，不能猜配
            throw new ServiceException("执行失败：指定调整行的归属期间/合同已变化，"
                + "请撤回该调整单后按最新明细重新发起：adjustId={}, factId={}", adjust.getId(), factId);
        }
        List<PerformanceFact> successors = activeById.values().stream()
            .filter(f -> Objects.equals(f.getEmployeeId(), snapshot.getEmployeeId())
                && Objects.equals(f.getRoleType(), snapshot.getRoleType())
                && Objects.equals(f.getBizType(), snapshot.getBizType())
                && Objects.equals(f.getFeeItem(), snapshot.getFeeItem())
                && Objects.equals(f.getBusinessDate(), snapshot.getBusinessDate()))
            .toList();
        if (successors.size() == 1) {
            PerformanceFact successor = successors.get(0);
            log.warn("[调整单] 指定行已被冲销，自动续接到当前 ACTIVE 后继行：adjustId={}, oldFactId={}, "
                    + "newFactId={}, oldStatus={}, reversedReason={}",
                adjust.getId(), factId, successor.getId(), snapshot.getFactStatus().getCode(),
                snapshot.getReversedReason() == null ? null : snapshot.getReversedReason().getCode());
            // 改写内存中的 factId：后续 coveredIds 去重与 supersede 均按后继行执行
            target.setFactId(successor.getId());
            return successor;
        }
        throw new ServiceException("执行失败：指定调整行已被其他操作替换且无法唯一匹配当前明细（候选 {} 条），"
                + "请撤回该调整单后按最新明细重新发起：adjustId={}, factId={}",
            successors.size(), adjust.getId(), factId);
    }

    /**
     * 指定值模式执行：既有行按 payload.detailTargets 精确 supersede（金额 + 可选角色占比）。
     * <p>
     * 完整性校验：执行时 Σ指定行目标金额 + 未指定行当前金额 = 单据 targetAmount；
     * 若执行时合同明细已被其他调整单抢先执行导致指定行缺失/合计对不上，则拒绝执行并留痕，
     * 避免把单据目标金额错误落库。
     */
    private void executeContractAmountByTargets(PerformanceAdjust adjust, List<PerformanceFact> facts,
                                                AddMemberPayload payload, Long operatorId) {
        List<AdjustDetailTargetBo> targets = payload.getDetailTargets();
        Map<Long, PerformanceFact> factById = facts.stream()
            .collect(Collectors.toMap(PerformanceFact::getId, f -> f));

        // 完整性校验：指定行必须全部存在，且目标合计 + 未指定行当前金额 = 单据目标金额
        BigDecimal coveredSum = BigDecimal.ZERO;
        Set<Long> coveredIds = new HashSet<>();
        for (AdjustDetailTargetBo t : targets) {
            if (t == null || t.getFactId() == null || t.getTargetAmount() == null) {
                continue;
            }
            // 快照 factId 在审批期间被 supersede 时自动续接唯一后继行（失败则抛业务异常提示撤回重发）
            resolveTargetFactOrSuccessor(factById, t, adjust);
            if (!coveredIds.add(t.getFactId())) {
                throw new ServiceException("执行失败：多条指定行续接到了同一条当前明细，"
                    + "请撤回该调整单后重新编辑发起：adjustId={}, factId={}", adjust.getId(), t.getFactId());
            }
            coveredSum = coveredSum.add(t.getTargetAmount());
        }
        BigDecimal uncoveredSum = facts.stream()
            .filter(f -> !coveredIds.contains(f.getId()))
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (MoneyUtil.round2(coveredSum.add(uncoveredSum)).compareTo(MoneyUtil.round2(adjust.getTargetAmount())) != 0) {
            throw new ServiceException("执行失败：执行时合同业绩合计已变化（Σ指定值{} + 未指定行{} ≠ 目标金额{}），"
                    + "为避免金额错乱终止执行：adjustId={}",
                MoneyUtil.round2(coveredSum), MoneyUtil.round2(uncoveredSum),
                MoneyUtil.round2(adjust.getTargetAmount()), adjust.getId());
        }

        int affected = 0;
        for (AdjustDetailTargetBo t : targets) {
            if (t == null || t.getFactId() == null || t.getTargetAmount() == null) {
                continue;
            }
            PerformanceFact oldFact = factById.get(t.getFactId());
            BigDecimal current = oldFact.getPerformanceAmount() == null
                ? BigDecimal.ZERO : oldFact.getPerformanceAmount();
            BigDecimal target = MoneyUtil.round2(t.getTargetAmount());
            boolean amountChanged = target.compareTo(current) != 0;
            boolean ratioChanged = t.getShareRatio() != null
                && (oldFact.getShareRatio() == null || t.getShareRatio().compareTo(oldFact.getShareRatio()) != 0);
            if (!amountChanged && !ratioChanged) {
                continue;
            }
            PerformanceFact newFact = buildContractAdjustedFact(oldFact,
                MoneyUtil.round2(target.subtract(current)), t.getShareRatio(), adjust.getId());
            reverseService.supersede(oldFact.getId(), newFact, operatorId);
            affected++;
        }
        log.info("[调整单] 合同级金额调整（指定值模式）完成：adjustId={}, contractNo={}, 明细数={}, "
                + "实际调整条数={}, 目标金额={}",
            adjust.getId(), adjust.getContractNo(), facts.size(), affected,
            MoneyUtil.round2(adjust.getTargetAmount()));
    }

    /**
     * 构建合同级分摊后的新事实：按 performance_amount 口径计算新金额。
     */
    private PerformanceFact buildContractAdjustedFact(PerformanceFact oldFact, BigDecimal deltaPerformance,
                                                      Long adjustId) {
        return buildContractAdjustedFact(oldFact, deltaPerformance, null, adjustId);
    }

    /**
     * 重载：支持同时指定调整后角色占比（shareRatio 为空则继承旧行）。
     */
    private PerformanceFact buildContractAdjustedFact(PerformanceFact oldFact, BigDecimal deltaPerformance,
                                                      BigDecimal shareRatio, Long adjustId) {
        BigDecimal newPerformance = MoneyUtil.round2(
            oldFact.getPerformanceAmount().add(deltaPerformance));

        PerformanceFact newFact = copyFactBase(oldFact);
        newFact.setPerformanceAmount(newPerformance);
        if (shareRatio != null) {
            newFact.setShareRatio(shareRatio);
        }
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
        BigDecimal[] parts = MoneyUtil.allocateByAmount(amounts, deltaTotal);

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

    // ==================== 增加角色人（ADD_MEMBER，2026-09-28） ====================

    /**
     * 合同级金额调整（AMOUNT）指定值模式：校验明细指定行并序列化 payloadJson 快照。
     * <p>
     * 校验：指定行须归属本合同；Σ指定行目标金额 + 未指定行当前金额 = 目标金额（targetAmount）。
     * 同时生成 allocations 预演快照（before=当前金额、delta=目标-当前），供单据详情按行精确回填；
     * 执行时按指定值精确落库（见 {@link #executeContractAmountAdjust}），不再等比分摊。
     *
     * @param dto 创建请求（含 detailTargets / targetAmount）
     * @return payloadJson（明细指定值快照 + 分摊预演）
     */
    private String prepareContractAmountTargets(PerformanceAdjustCreateBo dto) {
        List<PerformanceFact> facts = factMapper.selectActiveFactsByContractNo(
            dto.getPeriod(), dto.getFactType(), dto.getContractNo());
        if (facts == null || facts.isEmpty()) {
            throw new ServiceException("合同下未找到有效业绩事实，无法发起调整：{}", dto.getContractNo());
        }
        Map<Long, PerformanceFact> factById = facts.stream()
            .collect(Collectors.toMap(PerformanceFact::getId, f -> f));

        List<AdjustDetailTargetBo> validTargets = new ArrayList<>();
        Map<Long, BigDecimal> targetAmountByFact = new HashMap<>();
        BigDecimal coveredSum = BigDecimal.ZERO;
        Set<Long> coveredIds = new HashSet<>();
        for (AdjustDetailTargetBo t : dto.getDetailTargets()) {
            if (t == null || t.getFactId() == null || t.getTargetAmount() == null) {
                continue;
            }
            if (factById.get(t.getFactId()) == null) {
                throw new ServiceException("指定调整行不在该合同业绩明细中：factId={}", t.getFactId());
            }
            if (t.getShareRatio() != null && t.getShareRatio().signum() <= 0) {
                throw new ServiceException("角色占比必须大于 0：factId={}", t.getFactId());
            }
            validTargets.add(t);
            coveredIds.add(t.getFactId());
            targetAmountByFact.put(t.getFactId(), MoneyUtil.round2(t.getTargetAmount()));
            coveredSum = coveredSum.add(t.getTargetAmount());
        }
        BigDecimal uncoveredSum = facts.stream()
            .filter(f -> !coveredIds.contains(f.getId()))
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (dto.getTargetAmount() == null
            || MoneyUtil.round2(coveredSum.add(uncoveredSum)).compareTo(MoneyUtil.round2(dto.getTargetAmount())) != 0) {
            throw new ServiceException("明细调整后金额合计({})与目标金额({})不一致，请检查录入或刷新数据后重试",
                MoneyUtil.round2(coveredSum.add(uncoveredSum)), dto.getTargetAmount());
        }

        // 预演快照：复用 Alloc 结构（before=当前金额、delta=目标-当前），详情展示与执行共用
        Set<Long> factEmployeeIds = facts.stream()
            .map(PerformanceFact::getEmployeeId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());
        Map<Long, String> factNameMap = new HashMap<>();
        for (Map<String, Object> row : adjustMapper.employeeNames(factEmployeeIds.stream().toList())) {
            factNameMap.put(((Number) row.get("employeeId")).longValue(), String.valueOf(row.get("employeeName")));
        }
        List<AddMemberPayload.Alloc> allocations = new ArrayList<>();
        for (PerformanceFact f : facts) {
            AddMemberPayload.Alloc alloc = new AddMemberPayload.Alloc();
            alloc.setFactId(f.getId());
            alloc.setEmployeeId(f.getEmployeeId());
            alloc.setEmployeeName(f.getEmployeeId() != null ? factNameMap.get(f.getEmployeeId()) : null);
            alloc.setBefore(f.getPerformanceAmount());
            BigDecimal current = f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount();
            BigDecimal target = targetAmountByFact.get(f.getId());
            alloc.setDelta(target == null ? BigDecimal.ZERO : MoneyUtil.round2(target.subtract(current)));
            allocations.add(alloc);
        }

        AddMemberPayload payload = new AddMemberPayload();
        payload.setDetailTargets(validTargets);
        payload.setContractTotal(MoneyUtil.round2(facts.stream()
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add)));
        payload.setAllocations(allocations);
        return JsonUtils.toJsonString(payload);
    }

    /**
     * 增加角色人创建准备：校验 + 分摊预演快照 + payload 构建。
     * <p>
     * 语义：给合同手工多加一个人分业绩——新人金额 X，优先从指定行（deductions）精确扣除，
     * 剩余由未指定行按业绩占比等比分摊，默认合同总额不变。执行见 {@link #executeAddMemberAdjust}。
     * <p>
     * 支持指定值模式（2026-09-28 可编辑表格）：dto.detailTargets 非空时，既有行按指定
     * 「调整后金额/占比」精确指定，跳过 deductions/等比分摊逻辑，payload 以 detailTargets
     * 为执行权威依据。自 2026-09-29 起允许同时调整合同总额（afterTotal ≠ contractTotal，
     * 前端提交前二次确认），不再强制 Σtargets + 新人金额 = 原合同总额。
     * <p>
     * 本方法除构建 payload 外，还会回填 dto 的 employeeId/deptId（=新角色人）、
     * targetAmount（=新人金额，供后续统一构建调整单使用）、originalPeriod（强制置空，
     * ADD_MEMBER 的新事实必然挂到合同既有事实所在期间，跨月无意义）。
     *
     * @param dto 创建请求（含 newEmployeeId/newRoleType/newAmount/newShareRatio/newDeptId/deductions/detailTargets）
     * @return payloadJson（新角色人快照 + 指定扣除/明细指定值 + 分摊预演）
     */
    private String prepareAddMember(PerformanceAdjustCreateBo dto) {
        if (StringUtils.isNotBlank(dto.getOriginalPeriod()) && !dto.getOriginalPeriod().equals(dto.getPeriod())) {
            throw new ServiceException("增加角色人仅支持当期调整，不支持跨月");
        }
        if (dto.getNewEmployeeId() == null) {
            throw new ServiceException("增加角色人缺少新角色人员工");
        }
        BigDecimal newX = dto.getNewAmount();
        if (newX == null || newX.signum() <= 0) {
            throw new ServiceException("增加角色人缺少业绩金额或金额不大于 0");
        }
        if (dto.getNewShareRatio() != null && dto.getNewShareRatio().signum() <= 0) {
            throw new ServiceException("新角色人业绩比例必须大于 0");
        }

        List<PerformanceFact> facts = factMapper.selectActiveFactsByContractNo(
            dto.getPeriod(), dto.getFactType(), dto.getContractNo());
        if (facts == null || facts.isEmpty()) {
            throw new ServiceException("合同下未找到有效业绩事实，无法增加角色人：{}", dto.getContractNo());
        }
        // 新角色人须不在合同既有有效事实中（已在的人直接走明细级金额调整）
        for (PerformanceFact f : facts) {
            if (dto.getNewEmployeeId().equals(f.getEmployeeId())) {
                throw new ServiceException("该员工已在此合同业绩中，请直接调整其业绩金额");
            }
        }
        BigDecimal total = facts.stream()
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<Long, PerformanceFact> factById = facts.stream()
            .collect(Collectors.toMap(PerformanceFact::getId, f -> f));

        // 指定值模式（可编辑表格，2026-09-28）：既有行按「调整后金额/占比」精确指定；
        // 非空时跳过 deductions 指定扣除与等比分摊逻辑。
        // 2026-09-29 起允许同时调整合同总额：afterTotal（=Σ指定行目标+未指定行现值+新人金额）
        // 可与 total 不等，前端提交前已二次确认；执行时以 afterTotal 做完整性校验。
        boolean hasTargets = dto.getDetailTargets() != null && !dto.getDetailTargets().isEmpty();
        List<AdjustDetailTargetBo> validTargets = new ArrayList<>();
        Map<Long, BigDecimal> targetAmountByFact = new HashMap<>();
        BigDecimal afterTotal;
        if (hasTargets) {
            BigDecimal coveredSum = BigDecimal.ZERO;
            Set<Long> coveredIds = new HashSet<>();
            for (AdjustDetailTargetBo t : dto.getDetailTargets()) {
                if (t == null || t.getFactId() == null || t.getTargetAmount() == null) {
                    continue;
                }
                if (factById.get(t.getFactId()) == null) {
                    throw new ServiceException("指定调整行不在该合同业绩明细中：factId={}", t.getFactId());
                }
                if (t.getShareRatio() != null && t.getShareRatio().signum() <= 0) {
                    throw new ServiceException("角色占比必须大于 0：factId={}", t.getFactId());
                }
                validTargets.add(t);
                coveredIds.add(t.getFactId());
                targetAmountByFact.put(t.getFactId(), MoneyUtil.round2(t.getTargetAmount()));
                coveredSum = coveredSum.add(t.getTargetAmount());
            }
            BigDecimal uncoveredSum = facts.stream()
                .filter(f -> !coveredIds.contains(f.getId()))
                .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            afterTotal = MoneyUtil.round2(coveredSum.add(uncoveredSum).add(newX));
        } else {
            // 旧模式（deductions 指定扣除 + 等比分摊）合同总额必不变，新人金额不得超过原合计
            afterTotal = MoneyUtil.round2(total);
            if (newX.compareTo(total) > 0) {
                throw new ServiceException("新角色人业绩金额({})超过合同当前业绩合计({})，会导致其他明细为负",
                    MoneyUtil.round2(newX), MoneyUtil.round2(total));
            }
        }

        // 指定扣除：过滤合法行（amount>0 且 factId 归属本合同、单行扣除不超行金额）
        List<AdjustDeductionBo> validDeductions = new ArrayList<>();
        BigDecimal deductSum = BigDecimal.ZERO;
        if (!hasTargets && dto.getDeductions() != null) {
            for (AdjustDeductionBo d : dto.getDeductions()) {
                if (d == null || d.getFactId() == null || d.getAmount() == null || d.getAmount().signum() <= 0) {
                    continue;
                }
                PerformanceFact target = factById.get(d.getFactId());
                if (target == null) {
                    throw new ServiceException("指定扣除行不在该合同业绩明细中：factId={}", d.getFactId());
                }
                BigDecimal current = target.getPerformanceAmount() == null ? BigDecimal.ZERO : target.getPerformanceAmount();
                if (d.getAmount().compareTo(current) > 0) {
                    throw new ServiceException("指定扣除金额超过该行当前业绩：factId={}", d.getFactId());
                }
                validDeductions.add(d);
                deductSum = deductSum.add(d.getAmount());
            }
        }
        if (!hasTargets && deductSum.compareTo(newX) > 0) {
            throw new ServiceException("指定扣除合计({})超过新角色人业绩金额({})",
                MoneyUtil.round2(deductSum), MoneyUtil.round2(newX));
        }

        // 新角色人档案信息：工号/姓名入快照，部门用前端传值否则兜底档案部门
        List<Map<String, Object>> empRows = adjustMapper.employeeNames(List.of(dto.getNewEmployeeId()));
        if (empRows.isEmpty()) {
            throw new ServiceException("员工档案不存在：employeeId={}", dto.getNewEmployeeId());
        }
        if (dto.getNewDeptId() == null || dto.getNewDeptId() <= 0) {
            Object empDeptId = empRows.get(0).get("deptId");
            if (empDeptId == null) {
                throw new ServiceException("员工档案未配置部门，无法确定业绩归属部门：employeeId={}", dto.getNewEmployeeId());
            }
            dto.setNewDeptId(((Number) empDeptId).longValue());
        }
        // dto 回填供后续统一构建调整单（employeeId=新角色人、targetAmount=新人金额）
        dto.setEmployeeId(dto.getNewEmployeeId());
        dto.setDeptId(dto.getNewDeptId());
        dto.setTargetAmount(newX);
        dto.setOriginalPeriod(null);

        // 分摊预演快照（发起时金额）：指定行精确扣、剩余从未指定行按占比扣（尾差补最大行）；
        // 指定值模式下既有行 delta 已按 targets 精确计算，无需等比部分
        BigDecimal ratioDeduct = hasTargets ? BigDecimal.ZERO : MoneyUtil.round2(newX.subtract(deductSum));
        Set<Long> assignedFactIds = validDeductions.stream()
            .map(AdjustDeductionBo::getFactId).collect(Collectors.toSet());
        List<PerformanceFact> unassigned = facts.stream()
            .filter(f -> !assignedFactIds.contains(f.getId())).toList();
        if (ratioDeduct.signum() > 0 && unassigned.isEmpty()) {
            // 全部行都被指定时，剩余部分按全部行占比兜底
            unassigned = facts;
        }
        Map<Long, BigDecimal> ratioByFact = new HashMap<>();
        if (ratioDeduct.signum() > 0 && !unassigned.isEmpty()) {
            List<BigDecimal> unassignedAmounts = unassigned.stream()
                .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
                .toList();
            BigDecimal[] ratioParts = MoneyUtil.allocateByAmount(unassignedAmounts, ratioDeduct.negate());
            for (int i = 0; i < unassigned.size(); i++) {
                ratioByFact.put(unassigned.get(i).getId(), ratioParts[i]);
            }
        }
        List<AddMemberPayload.Alloc> allocations = new ArrayList<>();
        // 事实表无姓名列，批量从员工档案回填姓名入快照
        Set<Long> factEmployeeIds = facts.stream()
            .map(PerformanceFact::getEmployeeId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());
        Map<Long, String> factNameMap = new HashMap<>();
        for (Map<String, Object> row : adjustMapper.employeeNames(factEmployeeIds.stream().toList())) {
            factNameMap.put(((Number) row.get("employeeId")).longValue(), String.valueOf(row.get("employeeName")));
        }
        for (PerformanceFact f : facts) {
            AddMemberPayload.Alloc alloc = new AddMemberPayload.Alloc();
            alloc.setFactId(f.getId());
            alloc.setEmployeeId(f.getEmployeeId());
            alloc.setEmployeeName(f.getEmployeeId() != null ? factNameMap.get(f.getEmployeeId()) : null);
            alloc.setBefore(f.getPerformanceAmount());
            BigDecimal delta = BigDecimal.ZERO;
            if (hasTargets) {
                // 指定值模式：delta = 指定调整后金额 - 当前金额；未指定行不变
                BigDecimal target = targetAmountByFact.get(f.getId());
                if (target != null) {
                    BigDecimal current = f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount();
                    delta = MoneyUtil.round2(target.subtract(current));
                }
            } else {
                for (AdjustDeductionBo d : validDeductions) {
                    if (d.getFactId().equals(f.getId())) {
                        delta = delta.subtract(d.getAmount());
                        break;
                    }
                }
                BigDecimal part = ratioByFact.get(f.getId());
                if (part != null) {
                    delta = delta.add(part);
                }
            }
            alloc.setDelta(MoneyUtil.round2(delta));
            allocations.add(alloc);
        }

        AddMemberPayload payload = new AddMemberPayload();
        payload.setNewEmployeeId(dto.getNewEmployeeId());
        payload.setEmployeeCode(toStringOrNull(empRows.get(0).get("employeeCode")));
        payload.setEmployeeName(toStringOrNull(empRows.get(0).get("employeeName")));
        payload.setDeptId(dto.getNewDeptId());
        payload.setRoleType(StringUtils.defaultIfBlank(dto.getNewRoleType(), "合作人"));
        payload.setAmount(MoneyUtil.round2(newX));
        payload.setNewShareRatio(dto.getNewShareRatio() == null
            ? null : MoneyUtil.round6(dto.getNewShareRatio()));
        payload.setContractTotal(MoneyUtil.round2(total));
        payload.setAfterTotal(afterTotal);
        if (hasTargets) {
            payload.setDetailTargets(validTargets);
        } else {
            payload.setDeductions(validDeductions);
        }
        payload.setAllocations(allocations);
        List<Map<String, Object>> dnRows = adjustMapper.deptNames(List.of(dto.getNewDeptId()));
        payload.setDeptName(dnRows.isEmpty() ? null : toStringOrNull(dnRows.get(0).get("deptName")));
        return JsonUtils.toJsonString(payload);
    }

    /**
     * 解析增加角色人快照，解析失败返回 null（执行按无指定扣除全等比分摊兜底）。
     */
    private AddMemberPayload parseAddMemberPayload(String payloadJson) {
        if (StringUtils.isBlank(payloadJson)) {
            return null;
        }
        try {
            return JsonUtils.parseObject(payloadJson, AddMemberPayload.class);
        } catch (Exception e) {
            log.warn("[调整单] 增加角色人快照解析失败，按全等比分摊兜底：payload={}", payloadJson, e);
            return null;
        }
    }

    /**
     * 增加角色人（ADD_MEMBER）详情展示（分摊语义与金额调整不同，独立处理）：
     * <ul>
     *   <li>未执行：既有行按发起时分摊快照（payload.allocations，factId 优先、employeeId 兜底）
     *       回填 deltaAmount / afterAmount；末尾追加新角色人虚拟行（amount=null、afterAmount=X、target=true）。</li>
     *   <li>已执行：SQL 查到的是执行后 ACTIVE 事实（含新角色人事实行），同金额调整的反转语义——
     *       afterAmount=当前值、amount=发起时快照 before、deltaAmount=二者之差；
     *       新角色人行（employeeId=payload.newEmployeeId）amount=0、deltaAmount=+X。</li>
     * </ul>
     * 快照缺失时降级为仅展示当前值（不追加分摊/虚拟行），不影响单据基础信息查看。
     */
    private void applyAddMemberDetail(PerformanceAdjust adjust, AdjustDetailVo dto, List<AdjustFactDetailVo> details) {
        boolean alreadyExecuted = adjust.getStatus() == AdjustStatus.EXECUTED;
        AddMemberPayload payload = parseAddMemberPayload(adjust.getPayloadJson());
        boolean isExpectType = FACT_TYPE_EXPECT.equals(
            StringUtils.isNotBlank(adjust.getFactType()) ? adjust.getFactType() : FACT_TYPE_EXPECT);

        // 快照 factId → 事实摘要（未执行时是当前 ACTIVE 行；已执行时旧事实已 REVERSED、
        // 当前行是同 source_key 的新事实），统一经 source_key 关联当前明细行
        Map<Long, AddMemberPayload.Alloc> allocByFact = new HashMap<>();
        Map<Long, AddMemberPayload.Alloc> allocByEmployee = new HashMap<>();
        Map<String, AddMemberPayload.Alloc> allocBySourceKey = new HashMap<>();
        Map<String, AddMemberPayload.Alloc> allocByEmpRole = new HashMap<>();
        if (payload != null && payload.getAllocations() != null) {
            Set<Long> allocFactIds = payload.getAllocations().stream()
                .map(AddMemberPayload.Alloc::getFactId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
            Map<Long, PerformanceFactSummaryDTO> allocFactById = new HashMap<>();
            if (!allocFactIds.isEmpty()) {
                for (PerformanceFactSummaryDTO f : factMapper.selectFactSummariesByIds(allocFactIds)) {
                    allocFactById.put(f.getFactId(), f);
                }
            }
            for (AddMemberPayload.Alloc a : payload.getAllocations()) {
                if (a.getFactId() != null) {
                    allocByFact.put(a.getFactId(), a);
                    PerformanceFactSummaryDTO snap = allocFactById.get(a.getFactId());
                    if (snap != null) {
                        if (StringUtils.isNotBlank(snap.getSourceKey())) {
                            allocBySourceKey.put(snap.getSourceKey(), a);
                        }
                        if (snap.getEmployeeId() != null && StringUtils.isNotBlank(snap.getRoleType())) {
                            allocByEmpRole.putIfAbsent(snap.getEmployeeId() + "|" + snap.getRoleType(), a);
                        }
                    }
                }
                if (a.getEmployeeId() != null) {
                    allocByEmployee.putIfAbsent(a.getEmployeeId(), a);
                }
            }
        }

        // 既有行：未执行=预演分摊（amount+delta=after）；已执行=反转语义（amount=before，after=当前值）
        for (AdjustFactDetailVo d : details) {
            AddMemberPayload.Alloc alloc = null;
            if (StringUtils.isNotBlank(d.getSourceKey())) {
                alloc = allocBySourceKey.get(d.getSourceKey());
            }
            if (alloc == null && d.getFactId() != null) {
                alloc = allocByFact.get(d.getFactId());
            }
            if (alloc == null && d.getEmployeeId() != null && StringUtils.isNotBlank(d.getRoleType())) {
                alloc = allocByEmpRole.get(d.getEmployeeId() + "|" + d.getRoleType());
            }
            if (alloc == null && d.getEmployeeId() != null) {
                alloc = allocByEmployee.get(d.getEmployeeId());
            }
            BigDecimal current = d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO;
            if (alreadyExecuted) {
                d.setAfterAmount(current);
                if (alloc != null && alloc.getBefore() != null) {
                    d.setAmount(alloc.getBefore());
                    d.setDeltaAmount(MoneyUtil.round2(current.subtract(alloc.getBefore())));
                } else {
                    d.setAmount(current);
                    d.setDeltaAmount(BigDecimal.ZERO);
                }
                d.setTarget(true);
            } else if (alloc != null) {
                d.setDeltaAmount(alloc.getDelta());
                d.setAfterAmount(MoneyUtil.round2(current.add(alloc.getDelta())));
                d.setTarget(true);
            } else {
                d.setDeltaAmount(BigDecimal.ZERO);
                d.setAfterAmount(current);
                d.setTarget(false);
            }
        }

        // 新角色人行：已执行=SQL 已查出其事实行，按 员工+角色 标记反转口径（原值 0）；
        // 未执行=合同里还没有此人，追加虚拟行展示
        if (alreadyExecuted) {
            for (AdjustFactDetailVo d : details) {
                if (payload != null && payload.getNewEmployeeId() != null
                    && payload.getNewEmployeeId().equals(d.getEmployeeId())
                    && (StringUtils.isBlank(payload.getRoleType())
                        || payload.getRoleType().equals(d.getRoleType()))) {
                    d.setAmount(BigDecimal.ZERO);
                    BigDecimal after = d.getAfterAmount() != null ? d.getAfterAmount() : BigDecimal.ZERO;
                    d.setDeltaAmount(MoneyUtil.round2(after));
                    d.setTarget(true);
                }
            }
        } else if (payload != null) {
            AdjustFactDetailVo member = new AdjustFactDetailVo();
            member.setEmployeeId(payload.getNewEmployeeId());
            member.setEmployeeCode(payload.getEmployeeCode());
            member.setEmployeeName(payload.getEmployeeName());
            member.setDeptPath(payload.getDeptName());
            member.setRoleType(payload.getRoleType());
            member.setRoleName(payload.getRoleType());
            member.setShareRatio(payload.getNewShareRatio());
            member.setExpectedAmount(null);
            member.setAmount(null);
            member.setAfterAmount(adjust.getTargetAmount());
            member.setDeltaAmount(adjust.getTargetAmount());
            member.setTarget(true);
            details.add(member);
        }

        // 折算金额 + 合计（口径与金额调整分支一致）
        Set<Long> detailFactIds = details.stream()
            .map(AdjustFactDetailVo::getFactId)
            .filter(f -> f != null)
            .collect(Collectors.toSet());
        Map<Long, BigDecimal> detailFactorMap = factConversionResolver.factorByFactIds(detailFactIds);
        for (AdjustFactDetailVo d : details) {
            BigDecimal factor = conversionFactorPort.factorOf(detailFactorMap, d.getFactId());
            d.setConvertedAmount(conversionFactorPort.convert(d.getAmount(), factor));
            d.setConvertedAfterAmount(conversionFactorPort.convert(d.getAfterAmount(), factor));
        }
        dto.setDetails(details);
        dto.setDetailCount(details.size());
        if (isExpectType) {
            dto.setExpectedTotal(details.stream()
                .map(d -> d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
            dto.setReceivedTotal(details.stream()
                .map(d -> d.getExpectedAmount() != null ? d.getExpectedAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        } else {
            dto.setExpectedTotal(details.stream()
                .map(d -> d.getExpectedAmount() != null ? d.getExpectedAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
            dto.setReceivedTotal(details.stream()
                .map(d -> d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        if (adjust.getTargetAmount() != null) {
            dto.setTargetAmount(adjust.getTargetAmount());
        }
    }

    /**
     * 执行增加角色人（ADD_MEMBER，合同级）：指定行精确扣除 + 剩余等比扣除 + 插入新事实。
     * <p>
     * 默认不变量：执行后合同业绩合计 = 执行前（新事实 +X，既有事实合计 -X）；
     * 指定值模式允许混合金额调整（payload.afterTotal ≠ 执行前合计），以快照 afterTotal 为准。
     * 指定扣除以 payloadJson 为权威依据（按 factId supersede，执行时按当前事实重校验）；
     * 等比部分按执行时当前金额重新分摊（发起后合同可能又发生其他调整），
     * 基准行=未被指定扣除的事实（按调整后的事实集合），全部行被指定时按全部行兜底。
     */
    private void executeAddMemberAdjust(PerformanceAdjust adjust, Long operatorId) {
        BigDecimal newAmount = adjust.getTargetAmount();
        if (newAmount == null || newAmount.signum() <= 0) {
            throw new ServiceException("增加角色人调整缺少新角色人业绩金额：adjustId={}", adjust.getId());
        }
        AddMemberPayload payload = parseAddMemberPayload(adjust.getPayloadJson());

        List<PerformanceFact> facts = factMapper.selectActiveFactsByContractNo(
            adjust.getPeriod(), adjust.getFactType(), adjust.getContractNo());
        if (facts == null || facts.isEmpty()) {
            throw new ServiceException("合同下未找到有效业绩事实：contractNo={}", adjust.getContractNo());
        }
        BigDecimal total = facts.stream()
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 总额不变场景新人金额不得超过合同合计（否则既有行必出现负数）；
        // 混合金额调整（payload.afterTotal ≠ 执行时合计）时不受此限，完整性由指定值执行分支按快照校验
        boolean totalChanged = payload != null && payload.getAfterTotal() != null
            && MoneyUtil.round2(payload.getAfterTotal()).compareTo(MoneyUtil.round2(total)) != 0;
        if (!totalChanged && newAmount.compareTo(total) > 0) {
            throw new ServiceException("新角色人业绩金额超过合同当前业绩合计，会导致负数：adjustId={}, newAmount={}, total={}",
                adjust.getId(), newAmount, total);
        }
        Long newEmployeeId = adjust.getEmployeeId();
        for (PerformanceFact f : facts) {
            if (newEmployeeId.equals(f.getEmployeeId())) {
                throw new ServiceException("该员工已在此合同业绩中，不能重复增加：adjustId={}, employeeId={}",
                    adjust.getId(), newEmployeeId);
            }
        }

        // 指定值模式（2026-09-28 可编辑表格）：既有行按 payload.detailTargets 精确 supersede，
        // 再插入新人事实行；Σtargets + 未指定行 + 新人金额 = 合同总额（不变）
        if (payload != null && payload.getDetailTargets() != null && !payload.getDetailTargets().isEmpty()) {
            executeAddMemberByTargets(adjust, facts, payload, newAmount, newEmployeeId, operatorId);
            return;
        }

        // 1. 指定扣除：按 factId 定位执行时事实（factId 已被其他调整 supersede 的行忽略）
        Map<Long, PerformanceFact> factById = facts.stream()
            .collect(Collectors.toMap(PerformanceFact::getId, f -> f));
        Set<Long> deductedNewFactIds = new HashSet<>();
        BigDecimal deductSum = BigDecimal.ZERO;
        if (payload != null && payload.getDeductions() != null) {
            for (AdjustDeductionBo d : payload.getDeductions()) {
                if (d == null || d.getFactId() == null || d.getAmount() == null || d.getAmount().signum() <= 0) {
                    continue;
                }
                PerformanceFact target = factById.get(d.getFactId());
                if (target == null) {
                    continue;
                }
                BigDecimal current = target.getPerformanceAmount() == null ? BigDecimal.ZERO : target.getPerformanceAmount();
                if (d.getAmount().compareTo(current) > 0) {
                    throw new ServiceException("指定扣除金额超过该行当前业绩：adjustId={}, factId={}",
                        adjust.getId(), d.getFactId());
                }
                PerformanceFact newFact = buildContractAdjustedFact(target, d.getAmount().negate(), adjust.getId());
                reverseService.supersede(target.getId(), newFact, operatorId);
                deductedNewFactIds.add(newFact.getId());
                deductSum = deductSum.add(d.getAmount());
            }
        }
        if (deductSum.compareTo(newAmount) > 0) {
            throw new ServiceException("指定扣除合计超过新角色人业绩金额：adjustId={}", adjust.getId());
        }

        // 2. 剩余等比扣除：基准=扣除后仍 ACTIVE 且非指定行的事实（指定行已精确扣过），全部行被指定时按全部行兜底
        BigDecimal ratioDeduct = MoneyUtil.round2(newAmount.subtract(deductSum));
        if (ratioDeduct.signum() > 0) {
            List<PerformanceFact> currentFacts = factMapper.selectActiveFactsByContractNo(
                adjust.getPeriod(), adjust.getFactType(), adjust.getContractNo());
            List<PerformanceFact> base = currentFacts.stream()
                .filter(f -> !deductedNewFactIds.contains(f.getId())).toList();
            if (base.isEmpty()) {
                base = currentFacts;
            }
            List<BigDecimal> amounts = base.stream()
                .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
                .toList();
            BigDecimal[] parts = MoneyUtil.allocateByAmount(amounts, ratioDeduct.negate());
            for (int i = 0; i < base.size(); i++) {
                if (MoneyUtil.isZero(parts[i])) {
                    continue;
                }
                PerformanceFact oldFact = base.get(i);
                PerformanceFact newFact = buildContractAdjustedFact(oldFact, parts[i], adjust.getId());
                reverseService.supersede(oldFact.getId(), newFact, operatorId);
            }
        }

        // 3. 新事实：period/签约日跟随合同既有事实（结佣按签约月），来源=手工
        factMapper.insert(buildNewMemberFact(facts.get(0), adjust, payload));
        log.info("[调整单-增加角色人] 执行完成：adjustId={}, contractNo={}, newEmployeeId={}, newAmount={}, 指定扣除={}",
            adjust.getId(), adjust.getContractNo(), adjust.getEmployeeId(), newAmount, deductSum);
    }

    /**
     * 增加角色人·指定值模式执行：既有行按 payload.detailTargets 精确 supersede
     * （金额 + 可选角色占比），再插入新角色人事实行。
     * <p>
     * 完整性校验：Σ指定行目标 + 未指定行当前金额 + 新人金额 = 执行时合同既有行合计
     * （合同总额不变）；执行时合计被其他调整单抢先执行而变化时拒绝执行并留痕。
     */
    private void executeAddMemberByTargets(PerformanceAdjust adjust, List<PerformanceFact> facts,
                                           AddMemberPayload payload, BigDecimal newAmount,
                                           Long newEmployeeId, Long operatorId) {
        Map<Long, PerformanceFact> factById = facts.stream()
            .collect(Collectors.toMap(PerformanceFact::getId, f -> f));
        BigDecimal total = facts.stream()
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal coveredSum = BigDecimal.ZERO;
        Set<Long> coveredIds = new HashSet<>();
        for (AdjustDetailTargetBo t : payload.getDetailTargets()) {
            if (t == null || t.getFactId() == null || t.getTargetAmount() == null) {
                continue;
            }
            // 快照 factId 在审批期间被 supersede 时自动续接唯一后继行（失败则抛业务异常提示撤回重发）
            resolveTargetFactOrSuccessor(factById, t, adjust);
            if (!coveredIds.add(t.getFactId())) {
                throw new ServiceException("执行失败：多条指定行续接到了同一条当前明细，"
                    + "请撤回该调整单后重新编辑发起：adjustId={}, factId={}", adjust.getId(), t.getFactId());
            }
            coveredSum = coveredSum.add(t.getTargetAmount());
        }
        BigDecimal uncoveredSum = facts.stream()
            .filter(f -> !coveredIds.contains(f.getId()))
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 完整性校验：执行时 Σ指定行目标 + 未指定行现值 + 新人金额 必须等于发起时快照的调整后总额
        // （payload.afterTotal；2026-09-29 前旧单该字段为空，回退为执行时合计=总额不变语义）。
        // 执行时合同被其他调整单抢先改动会导致等式不成立，拒绝执行并留痕，避免金额错乱。
        BigDecimal expectedAfterTotal = payload.getAfterTotal() != null
            ? MoneyUtil.round2(payload.getAfterTotal()) : MoneyUtil.round2(total);
        BigDecimal actualAfterTotal = MoneyUtil.round2(coveredSum.add(uncoveredSum).add(newAmount));
        if (actualAfterTotal.compareTo(expectedAfterTotal) != 0) {
            throw new ServiceException("执行失败：执行时合同业绩合计已变化（既有行指定值{} + 未指定行{} + 新人{} "
                    + "≠ 发起时调整后总额{}），为避免金额错乱终止执行：adjustId={}",
                MoneyUtil.round2(coveredSum), MoneyUtil.round2(uncoveredSum), MoneyUtil.round2(newAmount),
                expectedAfterTotal, adjust.getId());
        }

        for (AdjustDetailTargetBo t : payload.getDetailTargets()) {
            if (t == null || t.getFactId() == null || t.getTargetAmount() == null) {
                continue;
            }
            PerformanceFact oldFact = factById.get(t.getFactId());
            BigDecimal current = oldFact.getPerformanceAmount() == null
                ? BigDecimal.ZERO : oldFact.getPerformanceAmount();
            BigDecimal target = MoneyUtil.round2(t.getTargetAmount());
            boolean amountChanged = target.compareTo(current) != 0;
            boolean ratioChanged = t.getShareRatio() != null
                && (oldFact.getShareRatio() == null || t.getShareRatio().compareTo(oldFact.getShareRatio()) != 0);
            if (!amountChanged && !ratioChanged) {
                continue;
            }
            PerformanceFact newFact = buildContractAdjustedFact(oldFact,
                MoneyUtil.round2(target.subtract(current)), t.getShareRatio(), adjust.getId());
            reverseService.supersede(oldFact.getId(), newFact, operatorId);
        }

        factMapper.insert(buildNewMemberFact(facts.get(0), adjust, payload));
        log.info("[调整单-增加角色人] 执行完成（指定值模式）：adjustId={}, contractNo={}, newEmployeeId={}, "
                + "newAmount={}, 合同总额不变={}",
            adjust.getId(), adjust.getContractNo(), newEmployeeId, MoneyUtil.round2(newAmount),
            MoneyUtil.round2(total));
    }

    /**
     * 构建新角色人事实：以合同首条事实为模板复制合同/期间/金额口径等基础字段，
     * 人员信息覆盖为新角色人，来源=MANUAL、批次/归一化记录引用置空（不归属于任何导入批次）。
     * sourceKey 对齐导入格式（orderNo|contractNo|角色人系统号|费用项|角色类型），
     * 角色人系统号用 MANUAL-{adjustNo} 虚拟值，避开导入事实的幂等键。
     */
    private PerformanceFact buildNewMemberFact(PerformanceFact template, PerformanceAdjust adjust,
                                               AddMemberPayload payload) {
        PerformanceFact newFact = copyFactBase(template);
        newFact.setEmployeeId(adjust.getEmployeeId());
        if (payload != null) {
            newFact.setEmployeeExternalCode(payload.getEmployeeCode());
            newFact.setRoleType(payload.getRoleType());
            newFact.setRoleName(payload.getRoleType());
            if (payload.getDeptId() != null) {
                newFact.setDeptId(payload.getDeptId());
            }
            // 新角色人业绩比例：发起时显式指定则落库，未指定保持 null（不设置占比）
            if (payload.getNewShareRatio() != null) {
                newFact.setShareRatio(payload.getNewShareRatio());
            }
        }
        newFact.setPerformanceAmount(MoneyUtil.round2(adjust.getTargetAmount()));
        newFact.setSource(PerformanceSource.MANUAL);
        newFact.setBatchId(null);
        newFact.setNormalizedRecordId(null);
        newFact.setSourceKey(StringUtils.defaultString(template.getOrderNo()) + "|"
            + StringUtils.defaultString(template.getContractNo()) + "|MANUAL-" + adjust.getAdjustNo()
            + "|" + StringUtils.defaultString(template.getFeeItem()) + "|"
            + StringUtils.defaultString(newFact.getRoleType()));
        return newFact;
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
        newFact.setBusinessDate(firstDay.atStartOfDay());
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
     * 计算调整变动额：targetAmount - originalAmount。
     * 调整单表存的是目标金额，变动额通过此方法推导。
     */
    private BigDecimal deltaOf(PerformanceAdjust adjust) {
        BigDecimal target = adjust.getTargetAmount() != null ? adjust.getTargetAmount() : BigDecimal.ZERO;
        BigDecimal origin = adjust.getOriginalAmount() != null ? adjust.getOriginalAmount() : BigDecimal.ZERO;
        return target.subtract(origin);
    }

    // 按金额占比分摊方法已上移至 MoneyUtil.allocateByAmount（调整单详情展示与执行落库共用）

    /**
     * 计算调整前的当前金额（performance_amount 口径）。
     * <p>
     * 合同级：汇总该合同下全部 ACTIVE 事实的 performance_amount；
     * 明细级：取单条事实的 performance_amount。
     */
    private BigDecimal calculateCurrentAmount(PerformanceAdjustCreateBo dto, String scope) {
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
        newFact.setOrderNo(oldFact.getOrderNo());
        newFact.setContractNo(oldFact.getContractNo());
        newFact.setPropertyAddress(oldFact.getPropertyAddress());
        newFact.setFeeItem(oldFact.getFeeItem());
        newFact.setEmployeeId(oldFact.getEmployeeId());
        newFact.setEmployeeExternalCode(oldFact.getEmployeeExternalCode());
        newFact.setDeptId(oldFact.getDeptId());
        newFact.setRoleType(oldFact.getRoleType());
        newFact.setRoleName(oldFact.getRoleName());
        newFact.setShareRatio(oldFact.getShareRatio());
        newFact.setPerformanceAmount(oldFact.getPerformanceAmount());
        newFact.setEffectiveDate(oldFact.getEffectiveDate() != null
            ? oldFact.getEffectiveDate() : oldFact.getBusinessDate().toLocalDate());
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
