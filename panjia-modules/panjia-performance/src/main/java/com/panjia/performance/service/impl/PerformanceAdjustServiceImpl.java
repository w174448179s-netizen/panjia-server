package com.panjia.performance.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.common.util.DeptScopeUtils;
import com.panjia.performance.domain.AdjustStatus;
import com.panjia.performance.domain.AdjustType;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.IllegalStateTransitionException;
import com.panjia.performance.domain.PerformanceAdjust;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.PerformanceSource;
import com.panjia.performance.domain.ReceivedAdjust;
import com.panjia.performance.domain.bo.AdjustDetailTargetBo;
import com.panjia.performance.domain.bo.AdjustDeductionBo;
import com.panjia.performance.domain.bo.AddMemberItemBo;
import com.panjia.performance.domain.bo.AddMemberPayload;
import com.panjia.performance.domain.bo.ExpectSupplementBo;
import com.panjia.performance.domain.bo.ExpectSupplementItem;
import com.panjia.performance.domain.bo.ManualOffsetItem;
import com.panjia.performance.domain.bo.PerformanceAdjustCreateBo;
import com.panjia.performance.domain.vo.AdjustDetailVo;
import com.panjia.performance.domain.vo.AdjustFactDetailVo;
import com.panjia.performance.domain.bo.PerformanceAdjustBo;
import com.panjia.performance.mapper.PerformanceAdjustMapper;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.mapper.ReceivedAdjustMapper;
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
import com.panjia.contracts.port.ReceivedApplyPort;
import com.panjia.contracts.port.ReceivedRealFactPort;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    /** 实收调整单 mapper：新签联动实收时登记镜像单（实收调整菜单可见、可追溯） */
    private final ReceivedAdjustMapper receivedAdjustMapper;
    /** 双键未命中退化合同号匹配的安全闸门（同合同多订单禁退化、订单误填唯一订单才退化） */
    private final com.panjia.performance.service.BizKeyMatchGuard bizKeyMatchGuard;
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

    /**
     * 实收事实端口（新签调整审批通过后联动实收：业绩调整按比例、业绩扣减同额，直接生效不再审批）。
     * 实现 bean 在 panjia-received（performance 不反向依赖 received），ObjectProvider 惰性取用，
     * 端口实现缺失时跳过联动，保证本模块上下文可独立启动。
     */
    private final ObjectProvider<ReceivedRealFactPort> receivedRealFactPortProvider;

    /**
     * 实收审批单端口（合同「有新签、无实收」联动时直建一张审批通过的实收审批单）。
     * 实现 bean 在 panjia-received，ObjectProvider 惰性取用，缺失时跳过建单。
     */
    private final ObjectProvider<ReceivedApplyPort> receivedApplyPortProvider;

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
        // 回填业务类型：列表「调整对象」号源口径对齐新签明细（一手房/房产金融显订单号）
        fillBizTypes(records);
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
        // 合同级调整不回填员工（含新角色人的单例外：employeeId=第一个新角色人，列表需显示新人姓名）
        Set<Long> employeeIds = records.stream()
            .filter(r -> r.getEmployeeId() != null
                && (!SCOPE_CONTRACT.equals(r.getAdjustScope()) || hasNewMembers(r)))
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
            // 合同级：清空员工信息，避免误导（含新角色人的单例外：显示新角色人）
            if (SCOPE_CONTRACT.equals(r.getAdjustScope()) && !hasNewMembers(r)) {
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
     * 回填合同级单据的「调整后合同业绩合计」：解析 payload.afterTotal
     * （ADD_MEMBER 历史单与 AMOUNT/DEDUCT/INCREASE 三类型统一快照均含该字段）；
     * 旧快照无该字段时回退原合同总额（2026-09-29 前单据均为总额不变）。
     */
    private void fillAddMemberAfterTotal(List<PerformanceAdjust> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (PerformanceAdjust r : records) {
            if (!SCOPE_CONTRACT.equals(r.getAdjustScope())) {
                continue;
            }
            BigDecimal afterTotal = null;
            AddMemberPayload payload = parseAddMemberPayload(r.getPayloadJson());
            if (payload != null) {
                afterTotal = payload.getAfterTotal();
            }
            if (afterTotal == null && r.getAdjustType() != AdjustType.ADD_MEMBER) {
                continue;
            }
            r.setAfterTotalAmount(afterTotal != null ? MoneyUtil.round2(afterTotal)
                : (r.getOriginalAmount() != null ? MoneyUtil.round2(r.getOriginalAmount()) : null));
        }
    }

    /**
     * 批量回填业务类型（pj_perf_fact.biz_type）：列表「调整对象」号源口径对齐新签明细——
     * 一手房/房产金融显订单号，其它业务类型显合同号（前端 resolveBizNo 同口径取值）。
     * <p>
     * 一次 IN 查询（合同号+订单号合集双列 OR），内存按「期间 + 事实口径 + 订单号优先」精确配对，
     * 配对口径与 {@code selectContractTotalAmount} 一致，避免同合同号多订单/跨口径串值。
     * 调整单自身不存 biz_type（其为合同静态属性），故从事实表回填；事实全部冲销时留空，前端回退合同号。
     */
    private void fillBizTypes(List<PerformanceAdjust> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Set<String> keys = new HashSet<>();
        for (PerformanceAdjust r : records) {
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
        List<Map<String, Object>> rows = factMapper.selectBizTypesByKeys(new ArrayList<>(keys));
        if (rows.isEmpty()) {
            return;
        }
        for (PerformanceAdjust r : records) {
            if (StringUtils.isBlank(r.getContractNo()) && StringUtils.isBlank(r.getOrderNo())) {
                continue;
            }
            String factType = StringUtils.isNotBlank(r.getFactType()) ? r.getFactType() : FACT_TYPE_EXPECT;
            for (Map<String, Object> row : rows) {
                if (!factType.equals(String.valueOf(row.get("factType")))
                    || !r.getPeriod().equals(String.valueOf(row.get("period")))) {
                    continue;
                }
                String fContractNo = row.get("contractNo") == null ? null : String.valueOf(row.get("contractNo"));
                String fOrderNo = row.get("orderNo") == null ? null : String.valueOf(row.get("orderNo"));
                boolean matched;
                if (StringUtils.isNotBlank(r.getOrderNo())) {
                    // 有订单号：按订单号精确配对（贝壳侧订单号与合同号 1:1）
                    matched = r.getOrderNo().equals(fOrderNo);
                } else {
                    // 历史单据无订单号：合同号/订单号双列 OR 兼容
                    matched = r.getContractNo().equals(fContractNo) || r.getContractNo().equals(fOrderNo);
                }
                if (matched) {
                    Object bizType = row.get("bizType");
                    if (bizType != null) {
                        r.setBizType(String.valueOf(bizType));
                    }
                    break;
                }
            }
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

        // 门店/组别名称回填（与列表口径一致，详情接口历史遗漏）
        if (adjust.getDeptId() != null) {
            adjustMapper.deptNames(java.util.List.of(adjust.getDeptId())).stream().findFirst()
                .ifPresent(row -> dto.setDeptName(String.valueOf(row.get("deptName"))));
        }

        boolean isContractScope = SCOPE_CONTRACT.equals(adjust.getAdjustScope());
        String contractNo = adjust.getContractNo();
        Long factId = adjust.getFactId();
        String period = adjust.getPeriod();

        // 1. 查合同信息
        java.util.Map<String, Object> contractInfo = null;
        if (isContractScope && StringUtils.isNotBlank(contractNo)) {
            // 业绩冲正单的 period 是冲正目标月：发起审批时该月可能还没有事实行，
            // 必须跨期（period=null）查合同信息，否则订单号/房源地址/签约时间全空；
            // 新签补录同理（补录前合同可能只有 PERF_REAL 事实，新签期间无 PERF_EXPECT 行）
            String contractInfoPeriod = (adjust.getAdjustType() == AdjustType.MANUAL_OFFSET
                || adjust.getAdjustType() == AdjustType.SUPPLEMENT) ? null : period;
            contractInfo = factMapper.selectContractInfoByContractNo(contractInfoPeriod, adjust.getOrderNo(), contractNo);
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
        // 业绩冲正（MANUAL_OFFSET）：冲正明细清单存 payloadJson（员工/角色/金额），
        // 不修改既有明细——直接解析 payload 转明细行返回，无需查合同既有事实
        if (adjust.getAdjustType() == AdjustType.MANUAL_OFFSET) {
            dto.setDetails(buildOffsetDetailRows(adjust));
            dto.setDetailCount(dto.getDetails().size());
            dto.setExpectedTotal(adjust.getOriginalAmount());
            dto.setReceivedTotal(adjust.getTargetAmount());
            return dto;
        }
        // 新签补录（SUPPLEMENT）：补录参数整体存 payloadJson（ExpectSupplementBo），
        // 合同信息查不到时（新签期间尚无事实行）从 payload 回填，明细行直接由补录明细生成
        if (adjust.getAdjustType() == AdjustType.SUPPLEMENT) {
            ExpectSupplementBo bo = JsonUtils.parseObject(adjust.getPayloadJson(), ExpectSupplementBo.class);
            if (StringUtils.isBlank(dto.getReason())) {
                dto.setReason("新签补录"); // 兼容存量空原因单据
            }
            if (bo != null) {
                if (StringUtils.isBlank(dto.getOrderNo())) {
                    dto.setOrderNo(bo.getOrderNo());
                }
                if (StringUtils.isBlank(dto.getPropertyAddress())) {
                    dto.setPropertyAddress(bo.getPropertyAddress());
                }
                if (StringUtils.isBlank(dto.getBusinessDate()) && bo.getBusinessDate() != null) {
                    dto.setBusinessDate(bo.getBusinessDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                }
            }
            // 第三级兜底：实收 DRAFT 场景 pj_perf_fact 无 ACTIVE 事实、payload 快照缺字段时，
            // 从实收合同主表（pj_received_contract）补全订单号/房源地址/签约时间
            if (StringUtils.isBlank(dto.getOrderNo()) || StringUtils.isBlank(dto.getPropertyAddress())
                || StringUtils.isBlank(dto.getBusinessDate())) {
                ReceivedApplyPort applyPort = receivedApplyPort();
                if (applyPort != null) {
                    com.panjia.contracts.dto.ReceivedContractInfoDTO rcInfo =
                        applyPort.findContractInfo(adjust.getContractNo(), adjust.getOrderNo());
                    if (rcInfo != null) {
                        if (StringUtils.isBlank(dto.getOrderNo())) {
                            dto.setOrderNo(rcInfo.getOrderNo());
                        }
                        if (StringUtils.isBlank(dto.getPropertyAddress())) {
                            dto.setPropertyAddress(rcInfo.getPropertyAddress());
                        }
                        if (StringUtils.isBlank(dto.getBusinessDate()) && rcInfo.getBusinessDate() != null) {
                            dto.setBusinessDate(rcInfo.getBusinessDate()
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                        }
                    }
                }
            }
            dto.setDetails(buildSupplementDetailRows(adjust, bo));
            dto.setDetailCount(dto.getDetails().size());
            // 对应实收金额按行回填到补录明细（同工号+角色匹配），审批人逐行对照实收 vs 补录新签
            fillSupplementReceivedAmounts(adjust, bo, dto.getDetails());
            dto.setExpectedTotal(adjust.getOriginalAmount());
            dto.setReceivedTotal(adjust.getTargetAmount());
            return dto;
        }
        // 实收调整（RECEIVED_AMOUNT）已于 2026-10-12 拆至实收调整表/服务（pj_perf_received_adjust）
        if (StringUtils.isNotBlank(targetContractNo)) {
            List<AdjustFactDetailVo> details =
                factMapper.selectAdjustFactDetails(period, adjust.getOrderNo(), targetContractNo, factType);
            // 含新角色人的合同级单（ADD_MEMBER 历史单 / 三类型加人单）：
            // 分摊展示语义与纯金额调整不同，独立处理后直接返回
            if (hasNewMembers(adjust)) {
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
                if (alreadyExecuted && deltaByFact != null) {
                    // 执行后旧事实行 REVERSED、同 source_key 生成新 ACTIVE 行（新 factId），
                    // payload 快照记的是旧 factId，需按稳定业务键（员工+角色+source_key）重映，
                    // 否则每行 delta 全部 miss 为 0，表现为「合同金额变了、明细没变」
                    deltaByFact = remapExecutedDeltaByFact(deltaByFact, details);
                }
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
                // 明细级：执行后 adjust.factId 指向 REVERSED 旧行，先按业务键解析当前 ACTIVE 后继行，
                // 否则目标行匹配不上，表现为明细 delta 全部为 0
                Long targetFactId = factId;
                if (alreadyExecuted && factId != null) {
                    PerformanceFact oldTargetFact = factMapper.selectById(factId);
                    if (oldTargetFact != null) {
                        String oldKey = factBizKey(oldTargetFact.getEmployeeId(),
                            oldTargetFact.getRoleType(), oldTargetFact.getSourceKey());
                        targetFactId = details.stream()
                            .filter(d -> oldKey.equals(factBizKey(d.getEmployeeId(), d.getRoleType(), d.getSourceKey())))
                            .map(AdjustFactDetailVo::getFactId)
                            .findFirst()
                            .orElse(factId);
                    }
                }
                // 明细级：只标记目标行
                for (AdjustFactDetailVo d : details) {
                    if (d.getFactId() != null && d.getFactId().equals(targetFactId)) {
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
        // 业绩冲正固定合同级 + 应收口径
        if (adjustType == AdjustType.MANUAL_OFFSET) {
            scope = SCOPE_CONTRACT;
            dto.setAdjustScope(SCOPE_CONTRACT);
            dto.setFactType(FACT_TYPE_EXPECT);
        }
        // §4.1 业绩调整只改应收（PERF_EXPECT）：合同级 / 明细级均拦截
        // （实收调整 RECEIVED_AMOUNT 已于 2026-10-12 拆至实收调整表/服务，独立走 PERF_REAL）
        if (!FACT_TYPE_EXPECT.equals(dto.getFactType())) {
            throw new ServiceException("业绩调整仅允许调整应收业绩（PERF_EXPECT），实收业绩请走实收审批/结佣对齐流程");
        }
        if (SCOPE_CONTRACT.equals(scope)) {
            // 合同级（2026-10-14 重构）：业绩调整/业绩扣减/业绩增加（三类型均可增加角色人）、业绩冲正；
            // 增加角色人（ADD_MEMBER）已并入三类型，禁止新建（历史单保留展示）
            if (adjustType == AdjustType.ADD_MEMBER) {
                throw new ServiceException("「增加角色人」已并入业绩调整/业绩扣减/业绩增加，请重新选择调整类型");
            }
            if (adjustType != AdjustType.AMOUNT && adjustType != AdjustType.DEDUCT
                && adjustType != AdjustType.INCREASE && adjustType != AdjustType.MANUAL_OFFSET) {
                throw new ServiceException("合同级调整仅支持业绩调整、业绩扣减、业绩增加或业绩冲正");
            }
            if (StringUtils.isBlank(dto.getContractNo()) || StringUtils.isBlank(dto.getFactType())) {
                throw new ServiceException("合同级调整缺少合同号或事实口径");
            }
        } else if (dto.getFactId() == null) {
            throw new ServiceException("明细级调整缺少关联业绩事实");
        }

        // period 为空时从原事实取期间（同新签期间），避免调整单期间为空
        if (StringUtils.isBlank(dto.getPeriod())) {
            if (SCOPE_CONTRACT.equals(scope)) {
                List<PerformanceFact> templateFacts = loadActiveFactsForAdjust(
                    null, dto.getFactType(), dto.getOrderNo(), dto.getContractNo());
                if (templateFacts != null && !templateFacts.isEmpty()) {
                    dto.setPeriod(templateFacts.get(0).getPeriod());
                }
            } else {
                PerformanceFact fact = factMapper.selectById(dto.getFactId());
                if (fact != null) {
                    dto.setPeriod(fact.getPeriod());
                }
            }
            if (StringUtils.isBlank(dto.getPeriod())) {
                throw new ServiceException("无法确定调整期间，请录入目标月份");
            }
        }

        // 封账期间禁止发起调整：与执行时口径一致，提前暴露；执行时仍保留复核，防发起后封账
        String originalPeriodForCloseCheck = StringUtils.isNotBlank(dto.getOriginalPeriod())
            ? dto.getOriginalPeriod() : dto.getPeriod();
        assertPeriodNotClosed(originalPeriodForCloseCheck, "原业绩归属月");
        assertPeriodNotClosed(dto.getPeriod(), "调整生效月");

        // 明细级：校验该事实是否已被锁定结佣明细绑定
        if (!SCOPE_CONTRACT.equals(scope) && dto.getFactId() != null) {
            PerformanceFact fact = factMapper.selectById(dto.getFactId());
            if (fact != null && StringUtils.isNotBlank(fact.getContractNo())) {
                assertFactsNotLocked(List.of(fact), fact.getContractNo());
            }
        }

        // 1.5 合同存在已作废明细时禁止调整（作废为合同级操作，口径一致：先恢复合同业绩再调整）
        // 业绩冲正跨期查作废明细（冲正期间可能无事实，但原月有）
        if (SCOPE_CONTRACT.equals(scope)) {
            String voidedPeriod = adjustType == AdjustType.MANUAL_OFFSET ? null : dto.getPeriod();
            // 同合同号挂多订单时按订单号+合同号双键校验，避免跨订单误拦
            if (!loadVoidedFactsForAdjust(voidedPeriod, dto.getFactType(), dto.getOrderNo(), dto.getContractNo()).isEmpty()) {
                throw new ServiceException("该合同存在已作废的业绩明细，禁止调整；如需调整请先恢复合同业绩");
            }
        } else if (factMapper.countVoidedSiblingsByFactId(dto.getFactId()) > 0) {
            throw new ServiceException("该合同存在已作废的业绩明细，禁止调整；如需调整请先恢复合同业绩");
        }

        // ==================== 业绩冲正（MANUAL_OFFSET，2026-10-10） ====================
        // 合同级录入冲正/补录明细，审批通过后按清单循环创建 MANUAL 事实。
        // 快照必须先归一化：前端雪花 Long 经 JS Number 会精度丢失（...890946→...891000），
        // employeeId/factId 不可信，统一以「工号 + 角色」对齐 DB 真实员工/源事实后再落 payload。
        if (adjustType == AdjustType.MANUAL_OFFSET) {
            normalizeManualOffsetItems(dto);
        }

        // ==================== 合同级三类型（AMOUNT/DEDUCT/INCREASE，2026-10-14 重构） ====================
        // 三类型统一走「明细指定值 + 可选新角色人」prepare：总额规则（调整=总额不变/扣减=须减少/增加=
        // 须增加）与扣减实收不足拦截见 prepareContractTargets；明细指定值为空时 AMOUNT 保持旧等比分摊模式
        if ((adjustType == AdjustType.AMOUNT || adjustType == AdjustType.DEDUCT
            || adjustType == AdjustType.INCREASE) && SCOPE_CONTRACT.equals(scope)
            && dto.getDetailTargets() != null && !dto.getDetailTargets().isEmpty()) {
            dto.setPayloadJson(prepareContractTargets(dto, adjustType));
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
                    throw new ServiceException("业绩调整缺少目标金额");
                }
            }
            dto.setTargetAmount(targetAmt);
        } else if (adjustType == AdjustType.DEDUCT || adjustType == AdjustType.INCREASE) {
            // 业绩扣减/增加：targetAmount 由 prepareContractTargets 按明细指定值 + 新人金额算出（=调整后合计）
            if (targetAmt == null) {
                throw new ServiceException("业绩扣减/增加缺少明细指定值，无法确定调整后金额");
            }
            dto.setTargetAmount(targetAmt);
        } else if (adjustType == AdjustType.MANUAL_OFFSET) {
            // 冲正后合计 = 当前合同合计 + 冲正明细合计（正增负减）
            BigDecimal offsetSum = BigDecimal.ZERO;
            for (ManualOffsetItem item : dto.getOffsetItems()) {
                if (item.getAmount() != null) {
                    offsetSum = offsetSum.add(item.getAmount());
                }
            }
            targetAmt = MoneyUtil.round2(originalAmt.add(offsetSum));
            dto.setTargetAmount(targetAmt);
        }

        // 2.5 合同级调整：dto.contractNo 是前端业务键（订单号优先、合同号兜底），
        // 落库前归一化为事实表的真实合同号——否则调整单/详情把订单号当合同号展示，
        // 而事实表 contract_no / order_no 两列本就分开存储
        if (SCOPE_CONTRACT.equals(scope)) {
            normalizeContractBizKeys(dto);
            // 结佣已审批锁定则禁止发起新签调整（保护已审批结佣数据）。
            // 事实级口径：只拦本次将被 supersede 且已被锁定明细绑定的事实，同合同其他月份
            // 未结佣的新签仍可调整；业绩冲正不 supersede 老事实，跳过校验。
            if (adjustType != AdjustType.MANUAL_OFFSET) {
                String gatePeriod = StringUtils.isNotBlank(dto.getOriginalPeriod())
                    ? dto.getOriginalPeriod() : dto.getPeriod();
                assertFactsNotLocked(loadActiveFactsForAdjust(gatePeriod, dto.getFactType(),
                    dto.getOrderNo(), dto.getContractNo()), dto.getContractNo());
            }
        }

        // 2.6 在途互斥：同合同存在审批中（SUBMITTED/APPROVED）调整单时拒绝发起。
        // 调整单 payload 快照 factId，在途期间事实被先执行的单 supersede 后，
        // 后执行单会因指定行失效而卡死；同合同调整必须串行（撤回或等审批完结再发起）。
        assertNoInFlightAdjust(scope, dto.getContractNo(), null, dto.getPeriod(),
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
        adjust.setOrderNo(dto.getOrderNo());
        adjust.setFactType(dto.getFactType());
        adjust.setOriginalPeriod(dto.getOriginalPeriod());
        adjust.setTargetAmount(targetAmt);
        adjust.setReason(dto.getReason());
        adjust.setPayloadJson(dto.getPayloadJson());
        adjust.setStatus(AdjustStatus.SUBMITTED);
        adjust.setApplicantId(applicantId);
        adjust.setOriginalAmount(originalAmt);

        // 合同级调整前端可能未传员工/部门（合同聚合行无此信息），从该合同首条 ACTIVE 事实回填
        // 跨月调整时事实在 originalPeriod（原业绩归属月）；业绩冲正跨期查模板
        if (SCOPE_CONTRACT.equals(scope)
            && (dto.getDeptId() == null || dto.getDeptId() <= 0 || dto.getEmployeeId() == null)) {
            String factLoadPeriod;
            if (adjustType == AdjustType.MANUAL_OFFSET) {
                factLoadPeriod = null;
            } else {
                factLoadPeriod = StringUtils.isNotBlank(dto.getOriginalPeriod())
                    ? dto.getOriginalPeriod() : dto.getPeriod();
            }
            List<PerformanceFact> facts = loadActiveFactsForAdjust(
                factLoadPeriod, dto.getFactType(), dto.getOrderNo(), dto.getContractNo());
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

    // ==================== 新签补录（SUPPLEMENT，2026-10-28） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PerformanceAdjust createSupplement(ExpectSupplementBo bo, Long applicantId) {
        // 1. 基础校验
        if (bo == null) {
            throw new ServiceException("补录参数不能为空");
        }
        if (StringUtils.isBlank(bo.getPeriod())) {
            throw new ServiceException("新签结算月不能为空");
        }
        if (bo.getItems() == null || bo.getItems().isEmpty()) {
            throw new ServiceException("补录明细不能为空");
        }
        // 金额合计：全零拒绝
        BigDecimal targetAmount = BigDecimal.ZERO;
        for (ExpectSupplementItem item : bo.getItems()) {
            if (item.getAmount() != null && item.getAmount().signum() > 0) {
                targetAmount = targetAmount.add(item.getAmount());
            }
        }
        if (targetAmount.signum() <= 0) {
            throw new ServiceException("未录入有效新签金额（全部为零）");
        }

        // 1.1 金额上限校验：新签补录合计不得超过该实收单的实收合计（前端同口径校验，此处兜底）
        ReceivedApplyPort amountPort = receivedApplyPort();
        if (amountPort != null && StringUtils.isNotBlank(bo.getReceivedPeriod())) {
            BigDecimal receivedCap = amountPort.findReceivedAmount(
                bo.getContractNo(), bo.getOrderNo(), bo.getReceivedPeriod());
            if (receivedCap != null && targetAmount.compareTo(receivedCap) > 0) {
                throw new ServiceException("新签补录合计 {} 元超过实收合计 {} 元，请调整后再提交",
                    MoneyUtil.round2(targetAmount).toPlainString(), MoneyUtil.round2(receivedCap).toPlainString());
            }
        }

        // 2. 归一化：前端 JS Number 会把雪花 Long 取整（精度丢失），
        //    employeeId 不可信，统一按工号反查 pj_people_employee 修正
        normalizeSupplementItems(bo);

        // 3. 在途互斥：同订单同期间不能有审批中的 PERF_EXPECT 调整单
        //    （同合同多订单时按订单判定，避免 A 订单在途单挡住 B 订单补录）
        assertNoInFlightAdjust(SCOPE_CONTRACT, bo.getContractNo(), bo.getOrderNo(), bo.getPeriod(),
            null, FACT_TYPE_EXPECT, null);

        // 3.1 防重复补录：同一实收单（订单号 + 实收月）已存在补录单（审批中/已通过/已执行）时禁止再次补录；
        //     已拒绝/已取消的补录单不拦截，允许修正后重新发起；
        //     订单号会跨实收月重复（同订单每月一张实收单），合同号会跨订单重复，
        //     故判重键为「订单号 + 实收月」，订单号为空时回退「合同号 + 实收月」
        boolean hasOrderNo = StringUtils.isNotBlank(bo.getOrderNo());
        boolean hasReceivedPeriod = StringUtils.isNotBlank(bo.getReceivedPeriod());
        Long dupCount = adjustMapper.selectCount(new LambdaQueryWrapper<PerformanceAdjust>()
            .eq(PerformanceAdjust::getAdjustType, AdjustType.SUPPLEMENT)
            .eq(hasOrderNo, PerformanceAdjust::getOrderNo, bo.getOrderNo())
            .eq(!hasOrderNo, PerformanceAdjust::getContractNo, bo.getContractNo())
            .eq(hasReceivedPeriod, PerformanceAdjust::getReceivedPeriod, bo.getReceivedPeriod())
            .in(PerformanceAdjust::getStatus,
                AdjustStatus.SUBMITTED, AdjustStatus.APPROVED, AdjustStatus.EXECUTED));
        if (dupCount != null && dupCount > 0) {
            log.warn("[调整单] 重复补录拦截：orderNo={}, contractNo={}, receivedPeriod={}, period={}, 已有补录单数={}",
                bo.getOrderNo(), bo.getContractNo(), bo.getReceivedPeriod(), bo.getPeriod(), dupCount);
            throw new ServiceException("该实收单（订单号 {}，实收月 {}）已存在新签补录单（含审批中/已生效），不允许重复补录；"
                + "如需调整请先撤回在途单据，或对已生效业绩走业绩冲正",
                bo.getOrderNo(), bo.getReceivedPeriod());
        }

        // 4. 构建调整单
        PerformanceAdjust adjust = new PerformanceAdjust();
        adjust.setAdjustNo(generateAdjustNo());
        adjust.setPeriod(bo.getPeriod());
        adjust.setAdjustType(AdjustType.SUPPLEMENT);
        adjust.setAdjustScope(SCOPE_CONTRACT);
        adjust.setContractNo(bo.getContractNo());
        adjust.setOrderNo(bo.getOrderNo());
        adjust.setReceivedPeriod(bo.getReceivedPeriod());
        adjust.setFactType(FACT_TYPE_EXPECT);
        adjust.setTargetAmount(MoneyUtil.round2(targetAmount));
        adjust.setPayloadJson(JsonUtils.toJsonString(bo));
        // 补录无手工录入原因入口，默认填「新签补录」供审批详情/列表展示
        adjust.setReason("新签补录");
        adjust.setStatus(AdjustStatus.SUBMITTED);
        adjust.setApplicantId(applicantId);
        // 合同级 employee_id / dept_id 占位：dept_id 列 NOT NULL，
        // 回填链路 bo.deptId → 明细行 deptId → 员工主数据 deptId
        Long resolvedDeptId = bo.getDeptId();
        Long placeholderEmpId = null;
        for (ExpectSupplementItem item : bo.getItems()) {
            if (item.getAmount() != null && item.getAmount().signum() > 0 && item.getEmployeeId() != null) {
                placeholderEmpId = item.getEmployeeId();
                if (resolvedDeptId == null) {
                    resolvedDeptId = item.getDeptId();
                }
                break;
            }
        }
        adjust.setEmployeeId(placeholderEmpId);
        if (resolvedDeptId == null && placeholderEmpId != null) {
            List<Map<String, Object>> empRows = adjustMapper.employeeNames(List.of(placeholderEmpId));
            if (!empRows.isEmpty() && empRows.get(0).get("deptId") != null) {
                resolvedDeptId = ((Number) empRows.get(0).get("deptId")).longValue();
            }
        }
        if (resolvedDeptId == null) {
            throw new ServiceException("无法确定补录归属门店：请检查补录明细员工的部门档案");
        }
        adjust.setDeptId(resolvedDeptId);

        adjustMapper.insert(adjust);

        // 5. 发起审批流程（bizId=调整单ID），失败则整体回滚
        ApprovalStartCmd cmd = buildStartCmd(adjust);
        boolean started;
        try {
            started = approvalPort.startAndCompleteFirst(BizType.PERF_ADJUST, adjust.getId(), cmd);
        } catch (Exception e) {
            log.error("[新签补录] 审批流程发起异常：adjustId={}", adjust.getId(), e);
            throw new ServiceException("新签补录审批流程发起失败：{}", e.getMessage());
        }
        if (!started) {
            throw new ServiceException("新签补录审批流程发起失败");
        }

        // 6. 回填流程实例 ID
        try {
            Long instanceId = approvalPort.instanceId(BizType.PERF_ADJUST, adjust.getId());
            if (instanceId != null) {
                adjust.setProcessInstanceId(String.valueOf(instanceId));
                adjustMapper.updateById(adjust);
            }
        } catch (Exception e) {
            log.warn("[新签补录] 流程实例ID回填失败：adjustId={}", adjust.getId(), e);
        }

        log.info("[新签补录] 创建并提交审批成功：adjustId={}, adjustNo={}, contractNo={}, orderNo={}, targetAmount={}, applicantId={}",
            adjust.getId(), adjust.getAdjustNo(), bo.getContractNo(), bo.getOrderNo(), targetAmount, applicantId);
        return adjust;
    }

    /**
     * 新签补录明细归一化：前端 JS Number 会把雪花 Long 取整（如 2108395694695890946 → 2108395694695891000），
     * employeeId 不可信。统一按工号反查 pj_people_employee 修正：
     * <ul>
     *   <li>先按 employeeId 精确命中，未命中按 employeeExternalCode（工号）反查；</li>
     *   <li>员工不存在直接拒绝发起；</li>
     *   <li>修正后写回 item.employeeId，保证审批执行端 id 可信。</li>
     * </ul>
     */
    private void normalizeSupplementItems(ExpectSupplementBo bo) {
        List<ExpectSupplementItem> items = bo.getItems();
        // 收集所有工号
        List<String> codes = items.stream()
            .map(ExpectSupplementItem::getEmployeeExternalCode)
            .filter(StringUtils::isNotBlank).map(String::trim).distinct().toList();
        // 批量查员工（按工号）
        Map<String, Map<String, Object>> empByCode = codes.isEmpty() ? Map.of()
            : adjustMapper.employeesByCodes(codes).stream()
                .collect(Collectors.toMap(
                    m -> String.valueOf(m.get("employeeCode")),
                    m -> m, (a, b) -> a));
        // 批量查员工（按 id，兜底）
        List<Long> empIds = items.stream()
            .map(ExpectSupplementItem::getEmployeeId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Map<String, Object>> empById = empIds.isEmpty() ? Map.of()
            : adjustMapper.employeeNames(empIds).stream()
                .collect(Collectors.toMap(
                    m -> ((Number) m.get("employeeId")).longValue(),
                    m -> m, (a, b) -> a));

        for (ExpectSupplementItem item : items) {
            Long resolvedId = null;
            // 1) employeeId 精确命中
            if (item.getEmployeeId() != null && empById.containsKey(item.getEmployeeId())) {
                resolvedId = item.getEmployeeId();
            }
            // 2) 工号反查命中
            if (resolvedId == null && StringUtils.isNotBlank(item.getEmployeeExternalCode())) {
                Map<String, Object> emp = empByCode.get(item.getEmployeeExternalCode().trim());
                if (emp != null && emp.get("employeeId") != null) {
                    resolvedId = ((Number) emp.get("employeeId")).longValue();
                }
            }
            if (resolvedId == null) {
                throw new ServiceException("补录明细存在无法识别的员工：employeeId={}, employeeCode={}",
                    item.getEmployeeId(), item.getEmployeeExternalCode());
            }
            item.setEmployeeId(resolvedId);
            // 补写工号（如果前端没传或传了被修正后 id 对应的工号）
            if (StringUtils.isBlank(item.getEmployeeExternalCode())) {
                Map<String, Object> emp = empById.get(resolvedId);
                if (emp == null && StringUtils.isNotBlank(item.getEmployeeExternalCode())) {
                    emp = empByCode.get(item.getEmployeeExternalCode().trim());
                }
                if (emp != null) {
                    Object code = emp.get("employeeCode");
                    if (code != null) {
                        item.setEmployeeExternalCode(String.valueOf(code));
                    }
                }
            }
        }
    }

    /**
     * 新签补录审批通过后执行：创建 PERF_EXPECT ACTIVE MANUAL 事实并触发实收 DRAFT 重评。
     * <p>
     * 执行端从 adjust.payload_json 反序列化回 ExpectSupplementBo，
     * 员工 ID 已在 createSupplement 的归一化阶段修正，可直接使用。
     */
    private void executeSupplementAdjust(PerformanceAdjust adjust, Long operatorId) {
        ExpectSupplementBo bo = JsonUtils.parseObject(adjust.getPayloadJson(), ExpectSupplementBo.class);
        if (bo == null) {
            throw new ServiceException("补录参数为空，无法执行：adjustId={}", adjust.getId());
        }

        String orderNo = bo.getOrderNo();
        String contractNo = bo.getContractNo();
        java.time.LocalDate periodStart = YearMonth.parse(bo.getPeriod()).atDay(1);
        java.time.LocalDateTime businessDateTime = bo.getBusinessDate() != null
            ? bo.getBusinessDate() : periodStart.atStartOfDay();
        // 生成唯一 sourceKey 前缀：orderNo|contractNo|MANUAL-SUPPLEMENT|ts
        String keyBase = (orderNo != null ? orderNo : contractNo) + "|MANUAL-SUPPLEMENT|" + System.currentTimeMillis();

        // 批量预加载员工主数据（归一化已修正 id，这里主要取 deptId）
        java.util.List<Long> empIds = bo.getItems().stream()
            .map(ExpectSupplementItem::getEmployeeId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Map<Long, Map<String, Object>> empMap = empIds.isEmpty() ? Map.of()
            : adjustMapper.employeeNames(empIds).stream()
                .collect(Collectors.toMap(
                    m -> ((Number) m.get("employeeId")).longValue(),
                    m -> m, (a, b) -> a));

        int count = 0;
        for (ExpectSupplementItem item : bo.getItems()) {
            if (item.getAmount() == null || item.getAmount().signum() == 0) {
                continue;
            }
            PerformanceFact fact = new PerformanceFact();
            fact.setFactType(FactType.PERF_EXPECT);
            fact.setFactStatus(FactStatus.ACTIVE);
            fact.setSource(PerformanceSource.MANUAL);
            fact.setSourceKey(keyBase + "-" + count);
            fact.setPeriod(bo.getPeriod());
            fact.setBusinessDate(businessDateTime);
            fact.setEffectiveDate(periodStart);
            fact.setPerformanceAmount(item.getAmount());
            fact.setOrderNo(orderNo);
            fact.setContractNo(contractNo);
            fact.setBizType(bo.getBizType());
            fact.setPropertyAddress(bo.getPropertyAddress());
            fact.setFeeItem(bo.getFeeItem());
            fact.setEmployeeId(item.getEmployeeId());
            // 工号：补录明细快照有就用快照，没有从员工主数据取
            String empCode = item.getEmployeeExternalCode();
            if (StringUtils.isBlank(empCode)) {
                Map<String, Object> emp = empMap.get(item.getEmployeeId());
                if (emp != null && emp.get("employeeCode") != null) {
                    empCode = String.valueOf(emp.get("employeeCode"));
                }
            }
            fact.setEmployeeExternalCode(empCode);
            fact.setRoleType(item.getRoleType());
            fact.setRoleName(item.getRoleName());
            // 部门：明细有就用，没有从 bo.deptId 或员工主数据取
            Long deptId = item.getDeptId();
            if (deptId == null) {
                deptId = bo.getDeptId();
            }
            if (deptId == null) {
                Map<String, Object> emp = empMap.get(item.getEmployeeId());
                if (emp != null && emp.get("deptId") != null) {
                    deptId = ((Number) emp.get("deptId")).longValue();
                }
            }
            fact.setDeptId(deptId);
            fact.setShareRatio(item.getShareRatio());
            fact.setOperatorId(operatorId);
            factMapper.insert(fact);
            count++;
        }
        if (count == 0) {
            throw new ServiceException("补录明细全部为零，未生成任何新签事实");
        }

        // 触发实收补建单（无新签不建单，去草稿后由未绑定明细承载待匹配状态）
        try {
            receivedApplyPort().resolvePendingAfterNewSign(bo.getReceivedPeriod(), contractNo, orderNo, operatorId, null);
        } catch (Exception e) {
            log.warn("[新签补录执行] 触发实收补建单失败（事实已落库）：receivedPeriod={}, period={}, orderNo={}, contractNo={}, 原因={}",
                bo.getReceivedPeriod(), bo.getPeriod(), orderNo, contractNo, e.getMessage());
        }

        log.info("[新签补录执行] 完成：adjustId={}, adjustNo={}, period={}, contractNo={}, orderNo={}, 新增事实={}, operator={}",
            adjust.getId(), adjust.getAdjustNo(), bo.getPeriod(), contractNo, orderNo, count, operatorId);
    }

    /**
     * 调整链路统一装载 ACTIVE 事实：订单号+合同号双键精确优先；双键未命中时经
     * {@link BizKeyMatchGuard} 闸门判定（目标事实订单维度唯一才退化，兼容贝壳新签
     * order_no 误填成合同号的脏数据），同合同多订单禁止退化（返回空，由调用方报错/走人工）；
     * orderNo 为空直接按合同号旧口径。
     * <p>
     * 背景：同一合同号可能挂多个订单号，按合同号 OR 装载会把多订单明细混排，
     * 导致「指定调整行不在该合同业绩明细中」与金额合计口径错误。
     * 发起校验与执行落库必须共用本方法，保证两端集合口径一致。
     *
     * @param period     归属期间（可空=不限期间；闸门计数同口径）
     * @param factType   事实口径
     * @param orderNo    订单号（可空）
     * @param contractNo 合同号（也兼容前端业务键：订单号优先的场景传入的是订单号）
     */
    private List<PerformanceFact> loadActiveFactsForAdjust(String period, String factType,
                                                           String orderNo, String contractNo) {
        if (StringUtils.isNotBlank(orderNo)) {
            // 订单号+合同号双键同时满足才精确唯一（订单号也可能重复：不同合同挂同订单号）
            List<PerformanceFact> byOrder = factMapper.selectActiveFactsByOrderAndContract(period, factType, orderNo, contractNo);
            if (byOrder != null && !byOrder.isEmpty()) {
                return byOrder;
            }
            // 双键未命中：仅当该合同 ACTIVE 事实订单维度唯一（含 order_no 误填合同号的脏数据）才退化
            if (bizKeyMatchGuard.canDegradeToContract(period, factType,
                List.of(FactStatus.ACTIVE.getCode()), contractNo)) {
                log.warn("[调整单] 双键未命中且合同事实订单唯一，退化合同号匹配：orderNo={}, contractNo={}, period={}, factType={}",
                    orderNo, contractNo, period, factType);
                return factMapper.selectActiveFactsByContractNo(period, factType, contractNo);
            }
            log.warn("[调整单] 双键未命中且该合同存在多个订单，禁止退化合同号匹配：orderNo={}, contractNo={}, period={}, factType={}",
                orderNo, contractNo, period, factType);
            return List.of();
        }
        return factMapper.selectActiveFactsByContractNo(period, factType, contractNo);
    }

    /**
     * 调整链路统一装载 VOIDED 事实：口径同 {@link #loadActiveFactsForAdjust}（双键精确优先，
     * 未命中经闸门判定才退化合同号；闸门统计 VOIDED 状态订单基数，保证作废-恢复可逆口径一致）。
     * 用于合同级调整发起前的「存在已作废明细禁止调整」校验，防止跨订单误拦/漏拦。
     */
    private List<PerformanceFact> loadVoidedFactsForAdjust(String period, String factType,
                                                            String orderNo, String contractNo) {
        if (StringUtils.isNotBlank(orderNo)) {
            List<PerformanceFact> byOrder = factMapper.selectVoidedFactsByOrderAndContract(period, factType, orderNo, contractNo);
            if (byOrder != null && !byOrder.isEmpty()) {
                return byOrder;
            }
            if (bizKeyMatchGuard.canDegradeToContract(period, factType,
                List.of(FactStatus.VOIDED.getCode()), contractNo)) {
                log.warn("[调整单] 双键未命中且合同作废事实订单唯一，退化合同号匹配：orderNo={}, contractNo={}, period={}, factType={}",
                    orderNo, contractNo, period, factType);
                return factMapper.selectVoidedFactsByContractNo(period, factType, contractNo);
            }
            log.warn("[调整单] 双键未命中且该合同存在多个订单的作废事实，禁止退化：orderNo={}, contractNo={}, period={}, factType={}",
                orderNo, contractNo, period, factType);
            return List.of();
        }
        return factMapper.selectVoidedFactsByContractNo(period, factType, contractNo);
    }

    /**
     * 合同级调整的业务键归一化：dto.contractNo 为前端业务键（订单号优先、合同号兜底），
     * 取该业务键下 ACTIVE 事实的真实 contract_no / order_no 回填 dto——否则调整单/详情把
     * 订单号当合同号展示，而事实表 contract_no / order_no 两列本就分开存储。
     * 订单号回填仅在事实集合唯一归属单订单时进行（跨订单混排时不猜配，保持空走合同号口径）。
     * 跨月调整时事实在 originalPeriod（原业绩归属月）；业绩冲正跨期查模板。
     */
    private void normalizeContractBizKeys(PerformanceAdjustCreateBo dto) {
        String factLoadPeriod = StringUtils.isNotBlank(dto.getOriginalPeriod())
            ? dto.getOriginalPeriod() : dto.getPeriod();
        // 业绩冲正：冲正期间可能无事实，跨期查模板
        if (AdjustType.MANUAL_OFFSET.getCode().equals(dto.getAdjustType())) {
            factLoadPeriod = null;
        }
        List<PerformanceFact> facts = loadActiveFactsForAdjust(
            factLoadPeriod, dto.getFactType(), dto.getOrderNo(), dto.getContractNo());
        dto.setContractNo(facts.stream()
            .map(PerformanceFact::getContractNo)
            .filter(StringUtils::isNotBlank)
            .findFirst()
            .orElse(dto.getContractNo()));
        if (StringUtils.isBlank(dto.getOrderNo())) {
            Set<String> orderNos = facts.stream()
                .map(PerformanceFact::getOrderNo)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
            if (orderNos.size() == 1) {
                dto.setOrderNo(orderNos.iterator().next());
            }
        }
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
     * <p>
     * 新签补录（不 supersede 既有事实、只新增事实）按订单维度互斥：同合同多订单时，
     * 一张补录单不应挡住同合同其他订单的补录；但历史无订单号的合同级在途单仍会作用于
     * 本订单事实，必须一并拦截。
     *
     * @param scope          调整范围（CONTRACT/DETAIL）
     * @param contractNo     合同号（合同级已归一化为事实表真实合同号；明细级可能为空）
     * @param orderNo        订单号（补录等按订单维度互斥时传；为空则按合同号/factId 判定）
     * @param period         调整生效月
     * @param originalPeriod 原业绩归属月（跨月调整）
     * @param factType       事实口径
     * @param factId         明细级调整关联事实 ID（合同级为空）
     */
    private void assertNoInFlightAdjust(String scope, String contractNo, String orderNo, String period,
                                        String originalPeriod, String factType, Long factId) {
        String originPeriod = StringUtils.isNotBlank(originalPeriod) ? originalPeriod : period;
        LambdaQueryWrapper<PerformanceAdjust> qw = new LambdaQueryWrapper<>();
        qw.eq(PerformanceAdjust::getFactType, factType)
            .in(PerformanceAdjust::getStatus, AdjustStatus.SUBMITTED, AdjustStatus.APPROVED)
            .and(w -> w.eq(PerformanceAdjust::getPeriod, originPeriod)
                .or().eq(PerformanceAdjust::getOriginalPeriod, originPeriod)
                .or().eq(PerformanceAdjust::getPeriod, period)
                .or().eq(PerformanceAdjust::getOriginalPeriod, period));
        if (StringUtils.isNotBlank(orderNo)) {
            // 订单维度互斥：本订单在途单，或历史无订单号的合同级在途单（后者同样作用于本订单事实）
            if (StringUtils.isNotBlank(contractNo)) {
                qw.eq(PerformanceAdjust::getContractNo, contractNo)
                    .and(w -> w.eq(PerformanceAdjust::getOrderNo, orderNo)
                        .or().isNull(PerformanceAdjust::getOrderNo));
            } else {
                qw.eq(PerformanceAdjust::getOrderNo, orderNo);
            }
        } else if (StringUtils.isNotBlank(contractNo)) {
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
            log.warn("[调整单] 在途互斥拦截：scope={}, contractNo={}, orderNo={}, period={}, originalPeriod={}, factId={}, 在途单数={}",
                scope, contractNo, orderNo, period, originalPeriod, factId, count);
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
        // 结佣已审批锁定则禁止执行新签调整（兜底：防发起后结佣变锁定）。
        // 事实级口径：只拦本次将被 supersede 且已被锁定明细绑定的事实。
        // MANUAL_OFFSET 和 SUPPLEMENT 均不 supersede 已有事实（前者冲正/补录、后者全新创建），跳过校验。
        if (adjust.getAdjustType() != AdjustType.MANUAL_OFFSET
            && adjust.getAdjustType() != AdjustType.SUPPLEMENT) {
            assertFactsNotLocked(loadActiveFactsForAdjust(originalPeriod, adjust.getFactType(),
                adjust.getOrderNo(), adjust.getContractNo()), adjust.getContractNo());
        }

        // 根据调整范围 + 类型执行不同逻辑
        if (SCOPE_CONTRACT.equals(adjust.getAdjustScope())) {
            // 合同级：MANUAL_OFFSET 业绩冲正；ADD_MEMBER 增加角色人（历史单，当期生效）；
            // AMOUNT/DEDUCT/INCREASE 三类型（2026-10-14）走「指定值 + 可选新人 + 实收联动」统一路径；
            // AMOUNT 旧单（无 afterTotal 快照）原月=调整月走金额调整，跨月走业绩冲销（§4.6）
            // originalPeriod 已在封账校验前解析
            if (adjust.getAdjustType() == AdjustType.MANUAL_OFFSET) {
                executeManualOffsetAdjust(adjust, operatorId);
            } else if (adjust.getAdjustType() == AdjustType.SUPPLEMENT) {
                executeSupplementAdjust(adjust, operatorId);
            } else if (adjust.getAdjustType() == AdjustType.ADD_MEMBER) {
                executeAddMemberAdjust(adjust, operatorId);
            } else if (adjust.getAdjustType() == AdjustType.DEDUCT
                || adjust.getAdjustType() == AdjustType.INCREASE
                || (adjust.getAdjustType() == AdjustType.AMOUNT && isUnifiedContractTargets(adjust))) {
                executeContractTargetsAdjust(adjust, operatorId);
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

    /** 实收事实端口（端口实现缺失时返回 null，实收联动跳过）。 */
    private ReceivedRealFactPort receivedRealPort() {
        return receivedRealFactPortProvider.getIfAvailable();
    }

    /** 实收审批单端口（惰性取用，实现缺失返回 null）。 */
    private ReceivedApplyPort receivedApplyPort() {
        return receivedApplyPortProvider.getIfAvailable();
    }

    /**
     * 校验待调整（将被 supersede）的新签事实是否已被锁定结佣明细绑定。
     * <p>事实级口径：只拦已结佣的那几条事实，同合同其他月份未结佣的新签仍可调整。
     * 端口实现缺失（本模块独立启动）或事实为空时跳过校验。
     */
    private void assertFactsNotLocked(List<PerformanceFact> facts, String contractNo) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        List<Long> factIds = facts.stream()
            .map(PerformanceFact::getId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (factIds.isEmpty()) {
            return;
        }
        CommissionGatePort gate = commissionGate();
        if (gate != null && gate.areFactsLockedByCommission(factIds)) {
            throw new ServiceException("合同 " + contractNo
                + " 的部分新签业绩已结佣并锁定，不能调整；如需调整请先作废对应结佣单");
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
        // 含新角色人的合同级单（ADD_MEMBER 历史单 / 三类型加人单）：employeeId=第一个新人，审批标题带上新人姓名
        if (hasNewMembers(adjust)) {
            subject = subject + "｜新人" + resolveEmployeeName(adjust.getEmployeeId());
        }
        String bizTitle = switch (adjust.getAdjustType()) {
            case MANUAL_OFFSET -> "业绩冲正";
            case ADD_MEMBER -> "增加角色人";
            // AMOUNT=业绩调整 / DEDUCT=业绩扣减 / INCREASE=业绩增加（2026-10-14 起 desc 即类型名）
            default -> adjust.getAdjustType().getDesc();
        };
        ApprovalStartCmd cmd = ApprovalStartCmd.of(
            text(adjust.getAdjustNo()),
            bizTitle + "｜" + subject
                + "｜账期" + text(adjust.getPeriod())
                + "｜类型" + text(adjust.getAdjustType().getDesc())
                + "｜冲正后金额" + text(adjust.getTargetAmount())
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

    /**
     * 业绩冲正详情·冲正明细行：解析 payloadJson（offsetItems 清单）→ AdjustFactDetailVo。
     * 展示口径与冲正弹窗一致：新签业绩=该行冲正前当前业绩（payload 快照）、
     * 变动=冲正金额（正补录/负冲正）、调整后=当前业绩+冲正金额；
     * 工号/门店/占比优先取保存时表格行快照，员工主数据兜底；折算后按源事实折算因子换算。
     */
    private List<AdjustFactDetailVo> buildOffsetDetailRows(PerformanceAdjust adjust) {
        List<ManualOffsetItem> items = JsonUtils.parseArray(adjust.getPayloadJson(), ManualOffsetItem.class);
        if (items == null || items.isEmpty()) {
            return new ArrayList<>();
        }
        // 批量查员工（姓名/工号/部门）+ 部门名，拼部门路径（快照缺失时兜底）
        List<Long> employeeIds = items.stream()
            .map(ManualOffsetItem::getEmployeeId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Map<String, Object>> empMap = adjustMapper.employeeNames(employeeIds).stream()
            .collect(Collectors.toMap(m -> ((Number) m.get("employeeId")).longValue(), m -> m, (a, b) -> a));
        // 工号兜底：历史 payload 的 employeeId 可能已被前端 JS Number 取整，按工号补查员工主数据
        List<String> employeeCodes = items.stream()
            .map(ManualOffsetItem::getEmployeeCode).filter(StringUtils::isNotBlank)
            .map(String::trim).distinct().toList();
        Map<String, Map<String, Object>> empByCode = adjustMapper.employeesByCodes(employeeCodes).stream()
            .collect(Collectors.toMap(m -> String.valueOf(m.get("employeeCode")), m -> m, (a, b) -> a));
        List<Long> deptIds = java.util.stream.Stream.concat(empMap.values().stream(), empByCode.values().stream())
            .map(m -> m.get("deptId")).filter(java.util.Objects::nonNull)
            .map(v -> ((Number) v).longValue()).distinct().toList();
        Map<Long, String> deptNameMap = adjustMapper.deptNames(deptIds).stream()
            .collect(Collectors.toMap(m -> ((Number) m.get("deptId")).longValue(),
                m -> String.valueOf(m.get("deptName")), (a, b) -> a));
        // 折算因子：按源事实批量取；无源事实的行取合同任一因子兜底，缺省 1
        java.util.Set<Long> srcFactIds = items.stream()
            .map(ManualOffsetItem::getFactId).filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, BigDecimal> factorMap = srcFactIds.isEmpty()
            ? Map.of() : factConversionResolver.factorByFactIds(srcFactIds);
        BigDecimal fallbackFactor = factorMap.values().stream().findFirst().orElse(BigDecimal.ONE);
        List<AdjustFactDetailVo> rows = new ArrayList<>(items.size());
        for (ManualOffsetItem item : items) {
            AdjustFactDetailVo row = new AdjustFactDetailVo();
            row.setFactId(item.getFactId());
            Map<String, Object> emp = item.getEmployeeId() == null ? null : empMap.get(item.getEmployeeId());
            if (emp == null && StringUtils.isNotBlank(item.getEmployeeCode())) {
                emp = empByCode.get(item.getEmployeeCode().trim());   // id 被取整时工号兜底
            }
            if (emp != null) {
                row.setEmployeeId(((Number) emp.get("employeeId")).longValue());
                row.setEmployeeName(toStringOrNull(emp.get("employeeName")));
                row.setEmployeeCode(StringUtils.isNotBlank(item.getEmployeeCode())
                    ? item.getEmployeeCode().trim() : toStringOrNull(emp.get("employeeCode")));
                Object deptId = emp.get("deptId");
                if (deptId != null) {
                    String deptName = deptNameMap.get(((Number) deptId).longValue());
                    if (StringUtils.isNotBlank(deptName)) {
                        row.setDeptPath(deptName);
                    }
                }
            } else {
                row.setEmployeeId(item.getEmployeeId());
                row.setEmployeeCode(item.getEmployeeCode());
            }
            if (StringUtils.isNotBlank(item.getDeptName())) {
                row.setDeptPath(item.getDeptName());    // 保存时表格行快照优先
            }
            row.setRoleType(item.getRoleType());
            row.setRoleName(item.getRoleName());
            row.setShareRatio(item.getShareRatio());
            BigDecimal original = item.getOriginalAmount() == null ? BigDecimal.ZERO : item.getOriginalAmount();
            BigDecimal amt = item.getAmount() == null ? BigDecimal.ZERO : item.getAmount();
            row.setAmount(original);                     // 冲正前当前业绩（弹窗行快照）
            row.setDeltaAmount(amt);                     // 变动 = 冲正金额（正补录/负冲正）
            row.setAfterAmount(original.add(amt));       // 调整后 = 当前业绩 + 冲正金额
            row.setTarget(true);
            BigDecimal factor = item.getFactId() != null && factorMap.containsKey(item.getFactId())
                ? factorMap.get(item.getFactId()) : fallbackFactor;
            row.setConvertedAmount(conversionFactorPort.convert(row.getAmount(), factor));
            row.setConvertedAfterAmount(conversionFactorPort.convert(row.getAfterAmount(), factor));
            rows.add(row);
        }
        return rows;
    }

    /**
     * 新签补录详情·补录明细行：解析 payloadJson（ExpectSupplementBo）→ AdjustFactDetailVo。
     * 语义：调整前业绩=0（补录前无新签事实）、变动=补录金额（正数）、调整后=补录金额。
     * 员工信息按 id 批量查主数据（id 已在创建时归一化），工号取快照兜底；
     * 折算因子取合同级因子（与详情头部 fillConvertedAmounts 同口径），缺省 1。
     */
    private List<AdjustFactDetailVo> buildSupplementDetailRows(PerformanceAdjust adjust, ExpectSupplementBo bo) {
        List<AdjustFactDetailVo> rows = new ArrayList<>();
        if (bo == null || bo.getItems() == null || bo.getItems().isEmpty()) {
            return rows;
        }
        List<ExpectSupplementItem> items = bo.getItems();
        // 批量查员工（姓名/工号/部门）+ 部门名
        List<Long> employeeIds = items.stream()
            .map(ExpectSupplementItem::getEmployeeId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Map<String, Object>> empMap = employeeIds.isEmpty() ? Map.of()
            : adjustMapper.employeeNames(employeeIds).stream()
                .collect(Collectors.toMap(m -> ((Number) m.get("employeeId")).longValue(), m -> m, (a, b) -> a));
        List<Long> deptIds = empMap.values().stream()
            .map(m -> m.get("deptId")).filter(Objects::nonNull)
            .map(v -> ((Number) v).longValue()).distinct().toList();
        Map<Long, String> deptNameMap = deptIds.isEmpty() ? Map.of()
            : adjustMapper.deptNames(deptIds).stream()
                .collect(Collectors.toMap(m -> ((Number) m.get("deptId")).longValue(),
                    m -> String.valueOf(m.get("deptName")), (a, b) -> a));
        // 折算因子：与详情头部同口径（合同级），无因子时缺省 1
        BigDecimal factor = factConversionResolver.factorByContract(adjust.getPeriod(), adjust.getContractNo(), FACT_TYPE_EXPECT);
        if (factor == null) {
            factor = BigDecimal.ONE;
        }
        for (ExpectSupplementItem item : items) {
            if (item.getAmount() == null || item.getAmount().signum() == 0) {
                continue;
            }
            AdjustFactDetailVo row = new AdjustFactDetailVo();
            Map<String, Object> emp = item.getEmployeeId() == null ? null : empMap.get(item.getEmployeeId());
            if (emp != null) {
                row.setEmployeeId(((Number) emp.get("employeeId")).longValue());
                row.setEmployeeName(toStringOrNull(emp.get("employeeName")));
                row.setEmployeeCode(StringUtils.isNotBlank(item.getEmployeeExternalCode())
                    ? item.getEmployeeExternalCode().trim() : toStringOrNull(emp.get("employeeCode")));
                Object deptId = emp.get("deptId");
                if (deptId != null) {
                    String deptName = deptNameMap.get(((Number) deptId).longValue());
                    if (StringUtils.isNotBlank(deptName)) {
                        row.setDeptPath(deptName);
                    }
                }
            } else {
                row.setEmployeeId(item.getEmployeeId());
                row.setEmployeeCode(item.getEmployeeExternalCode());
                row.setEmployeeName(item.getRoleName());
            }
            row.setRoleType(item.getRoleType());
            row.setRoleName(item.getRoleName());
            row.setShareRatio(item.getShareRatio());
            BigDecimal amt = MoneyUtil.round2(item.getAmount());
            row.setAmount(BigDecimal.ZERO);        // 补录前无新签事实
            row.setDeltaAmount(amt);               // 变动 = 补录金额
            row.setAfterAmount(amt);               // 调整后 = 补录金额
            row.setTarget(true);
            row.setConvertedAmount(conversionFactorPort.convert(row.getAmount(), factor));
            row.setConvertedAfterAmount(conversionFactorPort.convert(row.getAfterAmount(), factor));
            rows.add(row);
        }
        return rows;
    }

    /**
     * 新签补录详情·补录行回填「对应实收金额」：经端口查 pj_received_detail ACTIVE 行
     * （实收期间=bo.receivedPeriod），按 工号+角色类型 精确匹配，匹配不到再按工号合计兜底，
     * 均无匹配记 0（如新增角色人本就不在实收明细中）。端口缺失/无数据时全部记 0。
     */
    private void fillSupplementReceivedAmounts(PerformanceAdjust adjust, ExpectSupplementBo bo,
                                               List<AdjustFactDetailVo> rows) {
        if (bo == null || StringUtils.isBlank(bo.getReceivedPeriod()) || rows == null || rows.isEmpty()) {
            return;
        }
        ReceivedApplyPort applyPort = receivedApplyPort();
        if (applyPort == null) {
            return;
        }
        List<com.panjia.contracts.dto.ReceivedFactInfoDTO> facts =
            applyPort.findReceivedFacts(adjust.getContractNo(), adjust.getOrderNo(), bo.getReceivedPeriod());
        if (facts == null || facts.isEmpty()) {
            return;
        }
        // 两级匹配键：工号+角色 → 金额合计；工号 → 金额合计
        Map<String, BigDecimal> byEmpRole = new HashMap<>();
        Map<String, BigDecimal> byEmp = new HashMap<>();
        for (com.panjia.contracts.dto.ReceivedFactInfoDTO f : facts) {
            String code = StringUtils.trimToEmpty(f.getEmployeeExternalCode());
            if (code.isEmpty()) {
                continue;
            }
            BigDecimal amt = f.getAmount() == null ? BigDecimal.ZERO : f.getAmount();
            byEmpRole.merge(code + "|" + StringUtils.trimToEmpty(f.getRoleType()), amt, BigDecimal::add);
            byEmp.merge(code, amt, BigDecimal::add);
        }
        for (AdjustFactDetailVo row : rows) {
            String code = StringUtils.trimToEmpty(row.getEmployeeCode());
            BigDecimal received = byEmpRole.get(code + "|" + StringUtils.trimToEmpty(row.getRoleType()));
            if (received == null) {
                received = byEmp.get(code);
            }
            row.setReceivedAmount(received == null ? BigDecimal.ZERO : MoneyUtil.round2(received));
        }
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
        // 实收调整（RECEIVED_AMOUNT）已于 2026-10-12 物理拆至 pj_perf_received_adjust，本表不再有实收单
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
     * 已执行合同级调整的行 delta 重映。
     * <p>
     * payload 快照（allocations/detailTargets）记录的是执行前的 factId；执行时 supersede
     * 将旧行置 REVERSED 并生成同 source_key 的新 ACTIVE 行（新 factId）。详情明细查询只返回
     * ACTIVE 行，直接按 factId 匹配会全部 miss。这里按稳定业务键
     * （员工 + 角色 + source_key，source_key 内含费用项）把旧 factId 的 delta 映射到新行。
     *
     * @param deltaByOldFact key=执行前旧 factId，value=该行调整额
     * @param currentDetails 详情查询返回的当前 ACTIVE 明细
     * @return key=当前 ACTIVE factId 的 delta 映射；无法重映时返回原映射（行 delta 保持 0）
     */
    private Map<Long, BigDecimal> remapExecutedDeltaByFact(Map<Long, BigDecimal> deltaByOldFact,
                                                           List<AdjustFactDetailVo> currentDetails) {
        List<PerformanceFact> oldFacts = factMapper.selectBatchIds(deltaByOldFact.keySet());
        if (oldFacts == null || oldFacts.isEmpty()) {
            return deltaByOldFact;
        }
        Map<String, Long> bizKeyToOldFactId = new HashMap<>();
        for (PerformanceFact old : oldFacts) {
            bizKeyToOldFactId.put(
                factBizKey(old.getEmployeeId(), old.getRoleType(), old.getSourceKey()), old.getId());
        }
        Map<Long, BigDecimal> remapped = new HashMap<>();
        for (AdjustFactDetailVo d : currentDetails) {
            Long oldFactId = bizKeyToOldFactId.get(factBizKey(d.getEmployeeId(), d.getRoleType(), d.getSourceKey()));
            if (oldFactId != null) {
                remapped.put(d.getFactId(), deltaByOldFact.getOrDefault(oldFactId, BigDecimal.ZERO));
            }
        }
        return remapped.isEmpty() ? deltaByOldFact : remapped;
    }

    /** 明细业务键：员工 + 角色 + source_key（source_key 内含合同/费用项，足以区分同人多费项行） */
    private String factBizKey(Long employeeId, String roleType, String sourceKey) {
        return String.valueOf(employeeId) + '|' + StringUtils.trimToEmpty(roleType)
            + '|' + StringUtils.trimToEmpty(sourceKey);
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
        List<PerformanceFact> facts = loadActiveFactsForAdjust(
            adjust.getPeriod(), adjust.getFactType(), adjust.getOrderNo(), adjust.getContractNo());
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
        List<PerformanceFact> facts = loadActiveFactsForAdjust(
            originalPeriod, adjust.getFactType(), adjust.getOrderNo(), adjust.getContractNo());
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

    // ==================== 合同级三类型统一 prepare（2026-10-14） ====================

    /**
     * 合同级三类型（业绩调整 AMOUNT / 业绩扣减 DEDUCT / 业绩增加 INCREASE，2026-10-14 重构）
     * 统一 prepare：明细指定值 + 可选新角色人清单，校验并序列化 payloadJson 快照。
     * <p>
     * 总额规则（afterTotal = Σ指定行目标 + 未指定行现值 + Σ新人金额）：
     * <ul>
     *   <li>AMOUNT 业绩调整：afterTotal 必须等于原合同总额，否则拒绝保存；</li>
     *   <li>DEDUCT 业绩扣减：afterTotal 必须小于原总额（不再按实收余额阻断提交）；</li>
     *   <li>INCREASE 业绩增加：afterTotal 必须大于原总额。</li>
     * </ul>
     * 快照含 detailTargets（执行权威依据）+ newMembers + allocations 预演（before/delta，
     * 详情展示与实收联动共用）+ contractTotal/afterTotal；dto.targetAmount 回填为 afterTotal。
     * 实收联动规则见执行端 {@link #linkReceivedForAdjust}（2026-10-16 起三类型统一同额联动）。
     *
     * @param dto        创建请求（含 detailTargets / 可选 newMembers）
     * @param adjustType 调整类型（AMOUNT / DEDUCT / INCREASE）
     * @return payloadJson（明细指定值 + 新角色人 + 分摊预演快照）
     */
    private String prepareContractTargets(PerformanceAdjustCreateBo dto, AdjustType adjustType) {
        // 含新人时仅支持当期调整（新事实挂合同既有事实所在期间，跨月无意义）
        boolean hasMembers = dto.getNewMembers() != null
            && dto.getNewMembers().stream().anyMatch(m -> m != null && m.getEmployeeId() != null);
        if (hasMembers && StringUtils.isNotBlank(dto.getOriginalPeriod())
            && !dto.getOriginalPeriod().equals(dto.getPeriod())) {
            throw new ServiceException("增加角色人仅支持当期调整，不支持跨月");
        }

        List<PerformanceFact> facts = loadActiveFactsForAdjust(
            dto.getPeriod(), dto.getFactType(), dto.getOrderNo(), dto.getContractNo());
        if (facts == null || facts.isEmpty()) {
            throw new ServiceException("合同下未找到有效业绩事实，无法发起调整：{}", dto.getContractNo());
        }
        Map<Long, PerformanceFact> factById = facts.stream()
            .collect(Collectors.toMap(PerformanceFact::getId, f -> f));
        BigDecimal total = facts.stream()
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 1. 明细指定值校验：指定行须归属本合同；占比不为负
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
            if (t.getShareRatio() != null && t.getShareRatio().signum() < 0) {
                throw new ServiceException("角色占比不能为负：factId={}", t.getFactId());
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

        // 2. 新角色人清单（可空）：金额>0、占比不为负、清单内判重、不得已在合同既有事实中
        List<AddMemberItemBo> requestMembers = new ArrayList<>();
        if (dto.getNewMembers() != null) {
            for (AddMemberItemBo item : dto.getNewMembers()) {
                if (item != null && item.getEmployeeId() != null) {
                    requestMembers.add(item);
                }
            }
        }
        Set<Long> existingEmpIds = facts.stream()
            .map(PerformanceFact::getEmployeeId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Set<Long> requestEmpIds = new HashSet<>();
        for (AddMemberItemBo m : requestMembers) {
            if (m.getAmount() == null || m.getAmount().signum() <= 0) {
                throw new ServiceException("新角色人业绩金额缺失或不大于 0：employeeId={}", m.getEmployeeId());
            }
            if (m.getShareRatio() != null && m.getShareRatio().signum() < 0) {
                throw new ServiceException("新角色人业绩比例不能为负：employeeId={}", m.getEmployeeId());
            }
            if (!requestEmpIds.add(m.getEmployeeId())) {
                throw new ServiceException("同一员工不能重复添加为新角色人：employeeId={}", m.getEmployeeId());
            }
            if (existingEmpIds.contains(m.getEmployeeId())) {
                throw new ServiceException("员工已在此合同业绩中，请直接调整其业绩金额：employeeId={}", m.getEmployeeId());
            }
        }
        BigDecimal newX = requestMembers.stream()
            .map(m -> MoneyUtil.round2(m.getAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 3. 类型总额规则
        BigDecimal afterTotal = MoneyUtil.round2(coveredSum.add(uncoveredSum).add(newX));
        BigDecimal contractTotal = MoneyUtil.round2(total);
        if (adjustType == AdjustType.AMOUNT && afterTotal.compareTo(contractTotal) != 0) {
            throw new ServiceException("业绩调整必须保证修改后合同金额不变（当前 {} → {}），请检查各行金额",
                contractTotal, afterTotal);
        }
        if (adjustType == AdjustType.DEDUCT && afterTotal.compareTo(contractTotal) >= 0) {
            throw new ServiceException("业绩扣减要求修改后合同金额小于原金额（当前 {} → {}）",
                contractTotal, afterTotal);
        }
        if (adjustType == AdjustType.INCREASE && afterTotal.compareTo(contractTotal) <= 0) {
            throw new ServiceException("业绩增加要求修改后合同金额大于原金额（当前 {} → {}）",
                contractTotal, afterTotal);
        }

        // 4. 分摊预演快照（before=当前金额、delta=目标-当前；未指定行 delta=0），详情展示与实收联动共用
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

        // 6. 新角色人档案信息（一次 IN 批量查工号/姓名/部门），组装快照
        List<AddMemberPayload.NewMember> memberSnapshots = new ArrayList<>(requestMembers.size());
        if (!requestMembers.isEmpty()) {
            List<Long> memberIds = requestMembers.stream().map(AddMemberItemBo::getEmployeeId).toList();
            Map<Long, Map<String, Object>> empMap = new HashMap<>();
            for (Map<String, Object> row : adjustMapper.employeeNames(memberIds)) {
                empMap.put(((Number) row.get("employeeId")).longValue(), row);
            }
            for (AddMemberItemBo m : requestMembers) {
                Map<String, Object> empRow = empMap.get(m.getEmployeeId());
                if (empRow == null) {
                    throw new ServiceException("员工档案不存在：employeeId={}", m.getEmployeeId());
                }
                if (m.getDeptId() == null || m.getDeptId() <= 0) {
                    Object empDeptId = empRow.get("deptId");
                    if (empDeptId == null) {
                        throw new ServiceException("员工档案未配置部门，无法确定业绩归属部门：employeeId={}", m.getEmployeeId());
                    }
                    m.setDeptId(((Number) empDeptId).longValue());
                }
            }
            List<Long> memberDeptIds = requestMembers.stream()
                .map(AddMemberItemBo::getDeptId).filter(Objects::nonNull).distinct().toList();
            Map<Long, String> deptNameMap = new HashMap<>();
            for (Map<String, Object> row : adjustMapper.deptNames(memberDeptIds)) {
                deptNameMap.put(((Number) row.get("deptId")).longValue(), String.valueOf(row.get("deptName")));
            }
            for (AddMemberItemBo m : requestMembers) {
                Map<String, Object> empRow = empMap.get(m.getEmployeeId());
                AddMemberPayload.NewMember snap = new AddMemberPayload.NewMember();
                snap.setEmployeeId(m.getEmployeeId());
                snap.setEmployeeCode(toStringOrNull(empRow.get("employeeCode")));
                snap.setEmployeeName(toStringOrNull(empRow.get("employeeName")));
                snap.setDeptId(m.getDeptId());
                snap.setDeptName(deptNameMap.get(m.getDeptId()));
                snap.setRoleType(StringUtils.defaultIfBlank(m.getRoleType(), "合作人"));
                snap.setAmount(MoneyUtil.round2(m.getAmount()));
                snap.setNewShareRatio(m.getShareRatio() == null ? null : MoneyUtil.round6(m.getShareRatio()));
                memberSnapshots.add(snap);
            }
            // 列表展示新人姓名（对齐 ADD_MEMBER 口径）：employeeId/deptId=第一个新角色人
            AddMemberItemBo firstMember = requestMembers.get(0);
            dto.setEmployeeId(firstMember.getEmployeeId());
            dto.setDeptId(firstMember.getDeptId());
        }

        // 7. dto 回填 + payload 组装（targetAmount=调整后合计，执行按快照 detailTargets 落库）
        dto.setTargetAmount(afterTotal);
        AddMemberPayload payload = new AddMemberPayload();
        payload.setDetailTargets(validTargets);
        payload.setContractTotal(contractTotal);
        payload.setAfterTotal(afterTotal);
        payload.setAllocations(allocations);
        if (!memberSnapshots.isEmpty()) {
            payload.setMembers(memberSnapshots);
            // 顶层单新人字段写第一个新人：兼容审批标题、结佣镜像等旧读取方（执行/展示以 members 为权威）
            AddMemberPayload.NewMember firstSnap = memberSnapshots.get(0);
            payload.setNewEmployeeId(firstSnap.getEmployeeId());
            payload.setEmployeeCode(firstSnap.getEmployeeCode());
            payload.setEmployeeName(firstSnap.getEmployeeName());
            payload.setDeptId(firstSnap.getDeptId());
            payload.setDeptName(firstSnap.getDeptName());
            payload.setRoleType(firstSnap.getRoleType());
            payload.setAmount(firstSnap.getAmount());
            payload.setNewShareRatio(firstSnap.getNewShareRatio());
        }
        return JsonUtils.toJsonString(payload);
    }

    /**
     * 业绩冲正（MANUAL_OFFSET）创建前归一化：前端 JS Number 会把雪花 Long 取整
     * （如 2108395694695890946 → 2108395694695891000），快照里的 employeeId/factId 不可信。
     * <p>
     * 以「工号 + 角色类型」稳定业务键对齐 DB 真值后重写快照：
     * <ul>
     *   <li>employeeId：工号必填，先按 id 精确命中、未命中按工号反查 pj_people_employee，
     *       员工不存在直接拒绝发起；</li>
     *   <li>factId：前端 factId 精确命中合同 ACTIVE 事实优先，否则按（工号, 角色类型）匹配源事实；
     *       新增角色人行无源事实时保持 null（执行时仅按快照落人员字段）；</li>
     *   <li>归一化后的 items 重新序列化进 payload，详情展示与审批执行全部以此为准。</li>
     * </ul>
     */
    private void normalizeManualOffsetItems(PerformanceAdjustCreateBo dto) {
        List<ManualOffsetItem> items = dto.getOffsetItems();
        if (items == null || items.isEmpty()) {
            throw new ServiceException("冲正明细不能为空");
        }
        // 1. 员工主数据：工号是稳定业务键（必带），id 仅作优先精确匹配
        List<String> codes = items.stream()
            .map(ManualOffsetItem::getEmployeeCode)
            .filter(StringUtils::isNotBlank).map(String::trim).distinct().toList();
        // 逐行判空：同一工号可出现在多行（同一人多个角色），去重后数量不能用于判断空值
        if (items.stream().anyMatch(i -> StringUtils.isBlank(i.getEmployeeCode()))) {
            throw new ServiceException("冲正明细存在员工工号为空的行，无法提交");
        }
        Map<String, Map<String, Object>> empByCode = adjustMapper.employeesByCodes(codes).stream()
            .collect(Collectors.toMap(m -> String.valueOf(m.get("employeeCode")), m -> m, (a, b) -> a));
        List<Long> empIds = items.stream()
            .map(ManualOffsetItem::getEmployeeId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Map<String, Object>> empById = adjustMapper.employeeNames(empIds).stream()
            .collect(Collectors.toMap(m -> ((Number) m.get("employeeId")).longValue(), m -> m, (a, b) -> a));
        // 2. 合同全部有效应收事实（跨期）：factId 精确命中 + （工号,角色）兜底
        List<PerformanceFact> facts = loadActiveFactsForAdjust(
            null, FACT_TYPE_EXPECT, dto.getOrderNo(), dto.getContractNo());
        Map<Long, PerformanceFact> factById = facts == null ? new HashMap<>()
            : facts.stream().collect(Collectors.toMap(PerformanceFact::getId, f -> f, (a, b) -> a));
        Map<String, PerformanceFact> factByCodeRole = new HashMap<>();
        if (facts != null) {
            for (PerformanceFact f : facts) {
                if (StringUtils.isNotBlank(f.getEmployeeExternalCode()) && StringUtils.isNotBlank(f.getRoleType())) {
                    factByCodeRole.putIfAbsent(
                        f.getEmployeeExternalCode().trim() + "|" + f.getRoleType().trim(), f);
                }
            }
        }
        for (ManualOffsetItem item : items) {
            String code = item.getEmployeeCode().trim();
            Map<String, Object> emp = item.getEmployeeId() == null ? null : empById.get(item.getEmployeeId());
            if (emp == null) {
                emp = empByCode.get(code);   // id 精度丢失/缺失时工号兜底
            }
            if (emp == null) {
                throw new ServiceException("冲正明细员工不存在或已离职，工号：{}", code);
            }
            item.setEmployeeId(((Number) emp.get("employeeId")).longValue());
            item.setEmployeeCode(String.valueOf(emp.get("employeeCode")));
            if (StringUtils.isBlank(item.getRoleType())) {
                throw new ServiceException("冲正明细缺少所属角色，工号：{}", code);
            }
            item.setRoleType(item.getRoleType().trim());
            // factId 归一化：精确命中 → （工号,角色）匹配；都无（新增角色人行）则保持 null
            PerformanceFact src = item.getFactId() == null ? null : factById.get(item.getFactId());
            if (src == null) {
                src = factByCodeRole.get(code + "|" + item.getRoleType());
            }
            item.setFactId(src == null ? null : src.getId());
        }
        dto.setPayloadJson(JsonUtils.toJsonString(items));
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
     * 合同级调整单是否含新角色人（2026-10-14 起 AMOUNT/DEDUCT/INCREASE 三类型均可加人）：
     * ADD_MEMBER 历史单恒 true；三类型按 payload.members 判定（旧 AMOUNT 指定值单无 members 为 false）。
     * 列表员工回填、审批标题、详情展示的「加人」特判统一走本方法。
     */
    private boolean hasNewMembers(PerformanceAdjust adjust) {
        if (!SCOPE_CONTRACT.equals(adjust.getAdjustScope())) {
            return false;
        }
        if (adjust.getAdjustType() == AdjustType.ADD_MEMBER) {
            return true;
        }
        AddMemberPayload payload = parseAddMemberPayload(adjust.getPayloadJson());
        return payload != null && !payload.effectiveMembers().isEmpty();
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
        // 未执行=合同里还没有这些人，按快照逐个追加虚拟行展示（2026-10-11 起支持多人）
        List<AddMemberPayload.NewMember> pendingMembers = payload == null
            ? List.of() : payload.effectiveMembers();
        if (alreadyExecuted) {
            for (AdjustFactDetailVo d : details) {
                for (AddMemberPayload.NewMember m : pendingMembers) {
                    if (m.getEmployeeId() != null
                        && m.getEmployeeId().equals(d.getEmployeeId())
                        && (StringUtils.isBlank(m.getRoleType())
                            || m.getRoleType().equals(d.getRoleType()))) {
                        d.setAmount(BigDecimal.ZERO);
                        BigDecimal after = d.getAfterAmount() != null ? d.getAfterAmount() : BigDecimal.ZERO;
                        d.setDeltaAmount(MoneyUtil.round2(after));
                        d.setTarget(true);
                        break;
                    }
                }
            }
        } else {
            for (AddMemberPayload.NewMember m : pendingMembers) {
                BigDecimal memberAmount = MoneyUtil.round2(
                    m.getAmount() == null ? BigDecimal.ZERO : m.getAmount());
                AdjustFactDetailVo member = new AdjustFactDetailVo();
                member.setEmployeeId(m.getEmployeeId());
                member.setEmployeeCode(m.getEmployeeCode());
                member.setEmployeeName(m.getEmployeeName());
                member.setDeptPath(m.getDeptName());
                member.setRoleType(m.getRoleType());
                member.setRoleName(m.getRoleType());
                member.setShareRatio(m.getNewShareRatio());
                member.setExpectedAmount(null);
                member.setAmount(null);
                member.setAfterAmount(memberAmount);
                member.setDeltaAmount(memberAmount);
                member.setTarget(true);
                details.add(member);
            }
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
     * 是否走三类型统一执行路径（2026-10-14 起新建的 AMOUNT 合同级单）：
     * prepareContractTargets 快照必含 afterTotal；此前旧 AMOUNT 指定值单无该字段，保持旧执行路径。
     */
    private boolean isUnifiedContractTargets(PerformanceAdjust adjust) {
        AddMemberPayload payload = parseAddMemberPayload(adjust.getPayloadJson());
        return payload != null && payload.getAfterTotal() != null;
    }

    /**
     * 合同级三类型（业绩调整 AMOUNT / 业绩扣减 DEDUCT / 业绩增加 INCREASE，2026-10-14）统一执行：
     * 既有行按 payload.detailTargets 精确 supersede + 逐条插入新角色人事实行
     * （复用 {@link #executeAddMemberByTargets}，含 afterTotal 完整性校验与 factId 后继续接）；
     * 新签落库后三类型统一同额联动实收（{@link #linkReceivedForAdjust}）。
     */
    private void executeContractTargetsAdjust(PerformanceAdjust adjust, Long operatorId) {
        AddMemberPayload payload = parseAddMemberPayload(adjust.getPayloadJson());
        if (payload == null || payload.getDetailTargets() == null || payload.getDetailTargets().isEmpty()) {
            throw new ServiceException("调整单缺少明细指定值快照，无法执行：adjustId={}", adjust.getId());
        }
        List<AddMemberPayload.NewMember> members = payload.effectiveMembers();
        BigDecimal newAmount = members.stream()
            .map(m -> m.getAmount() == null ? BigDecimal.ZERO : MoneyUtil.round2(m.getAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<PerformanceFact> facts = loadActiveFactsForAdjust(
            adjust.getPeriod(), adjust.getFactType(), adjust.getOrderNo(), adjust.getContractNo());
        if (facts == null || facts.isEmpty()) {
            throw new ServiceException("合同下未找到有效业绩事实：contractNo={}", adjust.getContractNo());
        }
        // 新角色人不得已在合同当前事实中，快照内也不允许重复（同 ADD_MEMBER 执行口径）
        Set<Long> memberEmpIds = new HashSet<>();
        for (AddMemberPayload.NewMember m : members) {
            if (m.getEmployeeId() == null) {
                throw new ServiceException("新角色人快照缺少员工 ID：adjustId={}", adjust.getId());
            }
            if (!memberEmpIds.add(m.getEmployeeId())) {
                throw new ServiceException("新角色人快照存在重复员工，终止执行：adjustId={}, employeeId={}",
                    adjust.getId(), m.getEmployeeId());
            }
            for (PerformanceFact f : facts) {
                if (m.getEmployeeId().equals(f.getEmployeeId())) {
                    throw new ServiceException("该员工已在此合同业绩中，不能重复增加：adjustId={}, employeeId={}",
                        adjust.getId(), m.getEmployeeId());
                }
            }
        }

        executeAddMemberByTargets(adjust, facts, payload, members, newAmount, operatorId);
        // 实收联动：三类型统一同额联动（含新增角色人新建实收行，2026-10-16 简化）
        linkReceivedForAdjust(adjust, payload, operatorId);
        log.info("[调整单-三类型] 执行完成：adjustId={}, type={}, contractNo={}, memberCount={}, newAmount={}",
            adjust.getId(), adjust.getAdjustType().getCode(), adjust.getContractNo(), members.size(),
            MoneyUtil.round2(newAmount));
    }

    /**
     * 新签调整实收联动（2026-10-16「按角色行同额联动」；修正「扣减一条角色行、实收却被扣减到多条角色行」）。
     * <p>
     * 联动粒度 = 新签<b>事实行（角色行）</b>：某经纪人某个角色的新签变动 delta，
     * 只落到其实收明细中<b>同员工 + 同角色</b>的行上（同额），
     * 不再按「经纪人」把变动额分摊到其名下全部实收行（原实现按人聚合导致跨角色行扣减）。
     * <ul>
     *   <li>命中（员工+角色）实收行：按命中行金额占比分摊 delta 后逐行 supersede
     *       （{@link MoneyUtil#allocateByAmount}，尾差补最大行）；</li>
     *   <li>未命中同行、但该员工实收侧仍有其它行（角色口径不一致）：退化为按该员工实收行等比分摊（兜底，不新建行）；</li>
     *   <li>该员工实收侧完全无行（尚未到账）：按新签角色行镜像建实收行，金额 = delta（正负均建）；</li>
     *   <li>新增角色人：按（员工+角色）新建实收行，金额 = 该新人新签金额（同额）；</li>
     *   <li>合同整体无 ACTIVE 实收明细：自动建壳建明细，并在末尾直建一张审批通过的实收审批单；</li>
     *   <li>允许实收金额为负（{@code pj_received_detail.performance_amount} 无负值约束）：
     *       扣减额超过该角色行实收时不做截断、不阻断，严格同额扣减。</li>
     * </ul>
     * 端口缺失时跳过。
     */
    private void linkReceivedForAdjust(PerformanceAdjust adjust, AddMemberPayload payload, Long operatorId) {
        ReceivedRealFactPort port = receivedRealPort();
        if (port == null) {
            log.warn("[调整单-实收联动] 实收端口缺失，跳过联动：adjustId={}", adjust.getId());
            return;
        }
        String period = adjust.getPeriod();
        String orderNo = adjust.getOrderNo();
        String contractNo = adjust.getContractNo();

        List<PerformanceFactSummaryDTO> received = port.findActiveByContract(null, orderNo, contractNo);
        boolean hasAnyReceived = received != null && !received.isEmpty();
        // 联动前实收合计（镜像单 originalAmount）；联动累计变动额/是否动过行（镜像单 targetAmount 与是否登记判定）
        BigDecimal originalReceivedTotal = BigDecimal.ZERO;
        BigDecimal appliedDelta = BigDecimal.ZERO;
        boolean linkedTouched = false;
        // 按（员工 + 角色 + 费用项键）归组实收行：角色 trim 归一，空角色统一为空串；
        // 费用项键二分化（贷款服务费 / 非贷款服务费）——理房通空角色回填行=贷款服务费，
        // 只承接新签贷款服务费的变动，居间代理费等变动不得摊到该行（反之亦然）
        Map<Long, Map<String, List<PerformanceFactSummaryDTO>>> receivedByEmpRole = new HashMap<>();
        // 模板明细：有实收时用于 addMemberDetail 复制基础字段；无实收时用 createDetailFromContract
        Long templateDetailId = null;
        if (received != null) {
            for (PerformanceFactSummaryDTO r : received) {
                if (r.getAmount() != null) {
                    originalReceivedTotal = originalReceivedTotal.add(r.getAmount());
                }
                if (r.getEmployeeId() == null || r.getFactId() == null) {
                    continue;
                }
                receivedByEmpRole
                    .computeIfAbsent(r.getEmployeeId(), k -> new HashMap<>())
                    .computeIfAbsent(normalizeRole(r.getRoleType()) + feeKeyOf(r.getFeeItem()), k -> new ArrayList<>())
                    .add(r);
                if (templateDetailId == null) {
                    templateDetailId = r.getFactId();
                }
            }
        }

        // 新签事实行（角色行）变动额：allocations 为事实行粒度（含 factId），逐行按（员工+角色）归组
        List<AddMemberPayload.Alloc> allocs = payload == null || payload.getAllocations() == null
            ? List.of() : payload.getAllocations();
        Map<Long, PerformanceFactSummaryDTO> allocFactById = new HashMap<>();
        Set<Long> allocFactIds = allocs.stream()
            .map(AddMemberPayload.Alloc::getFactId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (!allocFactIds.isEmpty()) {
            for (PerformanceFactSummaryDTO f : factMapper.selectFactSummariesByIds(allocFactIds)) {
                if (f != null && f.getFactId() != null) {
                    allocFactById.put(f.getFactId(), f);
                }
            }
        }
        // 建壳兜底业务类型：同合同同业务类型，取任一新签事实的 bizType（取不到由适配器回源新签镜像）
        String bizType = allocFactById.values().stream()
            .map(PerformanceFactSummaryDTO::getBizType)
            .filter(StringUtils::isNotBlank)
            .findFirst().orElse(null);
        // 新签事实行（角色行）变动额：allocations 为事实行粒度（含 factId），
        // 逐行按（员工+角色+费用项键）归组——费用项键与实收侧同口径二分
        Map<Long, Map<String, BigDecimal>> deltaByEmpRole = new HashMap<>();
        for (AddMemberPayload.Alloc alloc : allocs) {
            if (alloc.getEmployeeId() == null) {
                continue;
            }
            PerformanceFactSummaryDTO f = alloc.getFactId() == null ? null : allocFactById.get(alloc.getFactId());
            String role = normalizeRole(f == null ? null : f.getRoleType())
                + feeKeyOf(f == null ? null : f.getFeeItem());
            deltaByEmpRole
                .computeIfAbsent(alloc.getEmployeeId(), k -> new HashMap<>())
                .merge(role, alloc.getDelta() == null ? BigDecimal.ZERO : alloc.getDelta(), BigDecimal::add);
        }
        // 新增角色人清单（同额新增实收行；其 delta 不在 allocations 中，单独处理）
        List<AddMemberPayload.NewMember> members = payload == null ? List.of() : payload.effectiveMembers();
        Set<Long> memberEmpIds = members.stream()
            .map(AddMemberPayload.NewMember::getEmployeeId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        if (!hasAnyReceived) {
            log.info("[调整单-实收联动] 合同无 ACTIVE 实收明细，自动建壳建虚拟实收：adjustId={}, contractNo={}",
                adjust.getId(), contractNo);
        }

        // 1. 逐新签角色行：按（员工+角色）同额联动实收（新增角色人除外，在步骤 3 处理）
        for (Map.Entry<Long, Map<String, BigDecimal>> empEntry : deltaByEmpRole.entrySet()) {
            Long employeeId = empEntry.getKey();
            // 新增角色人的实收行在步骤 3 统一处理
            if (memberEmpIds.contains(employeeId)) {
                continue;
            }
            Map<String, List<PerformanceFactSummaryDTO>> empReceived = receivedByEmpRole.get(employeeId);
            for (Map.Entry<String, BigDecimal> roleEntry : empEntry.getValue().entrySet()) {
                BigDecimal delta = MoneyUtil.round2(roleEntry.getValue());
                // 该新签角色行本次无变化（delta=0）
                boolean unchanged = MoneyUtil.isZero(delta);
                String role = roleEntry.getKey();
                List<PerformanceFactSummaryDTO> matched = empReceived == null ? null : empReceived.get(role);
                if (matched != null && !matched.isEmpty()) {
                    // 命中同（员工+角色）实收行：同额联动（含 INCREASE——新签增多少实收就增多少）
                    if (unchanged) {
                        continue;
                    }
                    adjustReceivedRows(port, matched, delta, adjust.getId(), operatorId);
                    appliedDelta = appliedDelta.add(delta);
                    linkedTouched = true;
                    log.info("[调整单-实收联动] adjustId={}, employeeId={}, role={}, 新签同额={}, 实收命中 {} 行同额联动",
                        adjust.getId(), employeeId, role, delta, matched.size());
                    continue;
                }
                if (empReceived != null && !empReceived.isEmpty()) {
                    // 角色口径不一致：退化为按该员工实收行等比分摊（兜底，不新建行，避免重复行）
                    if (unchanged) {
                        continue;
                    }
                    List<PerformanceFactSummaryDTO> all = empReceived.values().stream()
                        .flatMap(List::stream).toList();
                    adjustReceivedRows(port, all, delta, adjust.getId(), operatorId);
                    appliedDelta = appliedDelta.add(delta);
                    linkedTouched = true;
                    log.warn("[调整单-实收联动] adjustId={}, employeeId={}, 新签角色={} 在实收侧无同行，"
                        + "退化为按该员工 {} 条实收行等比分摊", adjust.getId(), employeeId, role, all.size());
                    continue;
                }
                // 该员工实收侧完全无行：
                // ① 合同已有实收、但该员工在实收侧不存在 → 不凭空建实收行（否则会出现「实收不存在的人」
                //    的虚假金额变动行）；仅告警，人数不一致问题由实收导入/审批环节拦截；
                // ② 合同整体无实收（hasAnyReceived=false）→ 按新签全字段镜像建行、金额=delta，
                //    且 delta=0 的角色行也一并建出，保证实收合同人员与新签一一对应（如 A-100/B+100/C0）。
                if (hasAnyReceived) {
                    log.warn("[调整单-实收联动] adjustId={}, employeeId={}, role={}, delta={} 在实收侧不存在"
                        + "（新签多出的人员），不凭空建实收行", adjust.getId(), employeeId, role, delta);
                    continue;
                }
                String roleArg = StringUtils.isBlank(role) ? null : role;
                Long createdId;
                if (templateDetailId != null) {
                    createdId = port.addMemberDetail(templateDetailId, employeeId, null, null,
                        roleArg, delta, null, operatorId, adjust.getId());
                } else {
                    createdId = port.createDetailFromContract(orderNo, contractNo, period, bizType,
                        employeeId, null, null, roleArg, delta, null, operatorId, adjust.getId());
                }
                log.info("[调整单-实收联动] 员工 {} 角色 {} 实收侧无明细，按新签同额 {} 自动建实收行："
                    + "adjustId={}, newDetailId={}", employeeId, role, delta, adjust.getId(), createdId);
                appliedDelta = appliedDelta.add(delta);
                linkedTouched = true;
            }
        }

        // 3. 新增角色人：按（员工+角色）同额新增实收行（金额 = 该新人新签金额）
        for (AddMemberPayload.NewMember m : members) {
            if (m.getEmployeeId() == null) {
                continue;
            }
            BigDecimal amt = MoneyUtil.round2(m.getAmount() == null ? BigDecimal.ZERO : m.getAmount());
            if (MoneyUtil.isZero(amt)) {
                continue;
            }
            String role = normalizeRole(m.getRoleType());
            Map<String, List<PerformanceFactSummaryDTO>> empReceived = receivedByEmpRole.get(m.getEmployeeId());
            List<PerformanceFactSummaryDTO> exist = empReceived == null ? null : empReceived.get(role);
            if (exist != null && !exist.isEmpty()) {
                // 该人实收侧已有同角色明细（异常场景）：同额加在其既有行上
                adjustReceivedRows(port, exist, amt, adjust.getId(), operatorId);
            } else if (templateDetailId != null) {
                port.addMemberDetail(templateDetailId, m.getEmployeeId(), m.getEmployeeCode(), m.getDeptId(),
                    m.getRoleType(), amt, m.getNewShareRatio(), operatorId, adjust.getId());
            } else {
                // 合同整体无实收：用无模板方法
                port.createDetailFromContract(orderNo, contractNo, period, bizType,
                    m.getEmployeeId(), m.getEmployeeCode(), m.getDeptId(), m.getRoleType(),
                    amt, m.getNewShareRatio(), operatorId, adjust.getId());
            }
            appliedDelta = appliedDelta.add(amt);
            linkedTouched = true;
            log.info("[调整单-实收联动] 新增角色人同额新增实收：adjustId={}, employeeId={}, role={}, amount={}",
                adjust.getId(), m.getEmployeeId(), role, amt);
        }

        // 4. 合同整体无实收：联动建出的虚拟实收明细须归入一张「审批通过」的实收审批单，
        //    否则实收审批单列表（按审批单维度展示）查不到这笔实收
        if (!hasAnyReceived) {
            ReceivedApplyPort applyPort = receivedApplyPort();
            if (applyPort != null) {
                int newApplies = applyPort.autoCreateApprovedForAdjust(period, orderNo, contractNo,
                    operatorId, adjust.getId());
                if (newApplies > 0) {
                    log.info("[调整单-实收联动] 无实收合同已直建审批通过实收审批单：adjustId={}, contractNo={}, 新建={}",
                        adjust.getId(), contractNo, newApplies);
                }
            } else {
                log.warn("[调整单-实收联动] 实收审批单端口缺失，跳过直建审批单：adjustId={}", adjust.getId());
            }
        }

        // 5. 登记实收调整镜像单（2026-10-17）：联动只动了 pj_received_detail 冲销链、没有实收调整单记录，
        //    导致「实收调整」菜单查不到、无法追溯。凡是动过实收行（supersede/新建）就补一张
        //    EXECUTED 镜像单（无审批流，与结佣调整登记新签镜像同款），原因里回链新签调整单号。
        if (linkedTouched) {
            recordLinkedReceivedAdjustMirror(adjust, bizType, originalReceivedTotal, appliedDelta, operatorId);
        }
    }

    /**
     * 新签联动实收的镜像单登记：写入 pj_perf_received_adjust（status=EXECUTED），
     * 让「实收调整」菜单可见可追溯；金额口径 = 联动前实收合计 → 联动后实收合计。
     */
    private void recordLinkedReceivedAdjustMirror(PerformanceAdjust adjust, String bizType,
                                                  BigDecimal originalTotal, BigDecimal appliedDelta, Long operatorId) {
        try {
            ReceivedAdjust mirror = new ReceivedAdjust();
            mirror.setAdjustNo(generateAdjustNo());
            mirror.setPeriod(adjust.getPeriod());
            mirror.setEmployeeId(adjust.getEmployeeId());
            mirror.setDeptId(adjust.getDeptId());
            mirror.setContractNo(adjust.getContractNo());
            mirror.setOrderNo(adjust.getOrderNo());
            mirror.setBizType(bizType);
            // 镜像类型：扣减/增加沿用新签类型；其余（AMOUNT 重分配/历史加人等）按 AMOUNT 语义
            AdjustType type = adjust.getAdjustType();
            mirror.setAdjustType(type == AdjustType.DEDUCT || type == AdjustType.INCREASE
                ? type.getCode() : AdjustType.AMOUNT.getCode());
            mirror.setOriginalAmount(MoneyUtil.round2(originalTotal));
            mirror.setTargetAmount(MoneyUtil.round2(originalTotal.add(appliedDelta)));
            String reason = "新签调整联动（新签调整单 " + adjust.getAdjustNo() + "）";
            if (StringUtils.isNotBlank(adjust.getReason())) {
                reason = reason + "：" + adjust.getReason();
            }
            mirror.setReason(reason);
            mirror.setStatus(AdjustStatus.EXECUTED);
            mirror.setApplicantId(adjust.getApplicantId() != null ? adjust.getApplicantId() : operatorId);
            mirror.setApproverId(operatorId);
            mirror.setApproveTime(LocalDateTime.now());
            mirror.setOperatorId(operatorId);
            mirror.setExecuteTime(LocalDateTime.now());
            receivedAdjustMapper.insert(mirror);
            log.info("[调整单-实收联动] 已登记实收调整镜像单：adjustId={}, mirrorNo={}, {} → {}",
                adjust.getId(), mirror.getAdjustNo(), mirror.getOriginalAmount(), mirror.getTargetAmount());
        } catch (Exception e) {
            // 镜像登记失败不阻断主流程（实收行已联动成功），仅告警由人工补录
            log.error("[调整单-实收联动] 实收调整镜像单登记失败（不影响联动结果）：adjustId={}", adjust.getId(), e);
        }
    }

    /** 角色口径归一：null → 空串；去除首尾空白，保证（员工+角色）匹配键稳定。 */
    private String normalizeRole(String roleType) {
        return roleType == null ? "" : roleType.trim();
    }

    /**
     * 费用项联动键（实收联动两侧同口径二分）：
     * 「贷款服务费」独立成键（理房通空角色回填行 ↔ 新签贷款服务费事实），
     * 其余费用项（含空）统一归空串键。与实收详情 SQL 的 IS DISTINCT FROM '贷款服务费' 同口径。
     */
    private String feeKeyOf(String feeItem) {
        return "贷款服务费".equals(feeItem) ? "|贷款服务费" : "";
    }

    /**
     * 对给定实收明细行按金额占比分摊 delta 后逐行 supersede（{@link MoneyUtil#allocateByAmount}，尾差补最大行）。
     * <p>金额允许为负：严格同额，扣减超实收不截断。
     * <p>supersede 后就地回写 DTO 的 factId/amount：receivedByEmpRole 是执行前一次性加载的快照，
     * 同一员工的多个角色行（或退化分支）可能先后命中同一条实收明细，不回写会让后续角色行
     * 再次冲销已 REVERSED 的旧行（生产 TG2603925248 余璇双角色案例）。
     */
    private void adjustReceivedRows(ReceivedRealFactPort port, List<PerformanceFactSummaryDTO> rows,
                                    BigDecimal delta, Long adjustId, Long operatorId) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<BigDecimal> amounts = rows.stream()
            .map(r -> r.getAmount() == null ? BigDecimal.ZERO : r.getAmount())
            .toList();
        BigDecimal[] parts = MoneyUtil.allocateByAmount(amounts, delta);
        for (int i = 0; i < rows.size(); i++) {
            if (MoneyUtil.isZero(parts[i])) {
                continue;
            }
            BigDecimal newAmount = MoneyUtil.round2(amounts.get(i).add(parts[i]));
            Long newDetailId = port.adjustDetailAmount(rows.get(i).getFactId(), newAmount, operatorId, adjustId);
            if (newDetailId != null) {
                rows.get(i).setFactId(newDetailId);
                rows.get(i).setAmount(newAmount);
            }
        }
    }

    /**
     * 执行增加角色人（ADD_MEMBER，合同级）：指定行精确扣除 + 剩余等比扣除 + 插入新事实。
     * <p>
     * 默认不变量：执行后合同业绩合计 = 执行前（新事实合计 +X，既有事实合计 -X）；
     * 指定值模式允许混合金额调整（payload.afterTotal ≠ 执行前合计），以快照 afterTotal 为准。
     * 指定扣除以 payloadJson 为权威依据（按 factId supersede，执行时按当前事实重校验）；
     * 等比部分按执行时当前金额重新分摊（发起后合同可能又发生其他调整），
     * 基准行=未被指定扣除的事实（按调整后的事实集合），全部行被指定时按全部行兜底。
     * <p>
     * 2026-10-11 起支持一张单多个新角色人：扣除分摊以全部新人金额合计为基准，
     * 最后按 payload.members 逐条插入新事实（旧快照回退顶层单新人字段）。
     */
    private void executeAddMemberAdjust(PerformanceAdjust adjust, Long operatorId) {
        AddMemberPayload payload = parseAddMemberPayload(adjust.getPayloadJson());
        List<AddMemberPayload.NewMember> members = payload == null
            ? List.of() : payload.effectiveMembers();
        if (members.isEmpty()) {
            throw new ServiceException("增加角色人调整缺少新角色人快照：adjustId={}", adjust.getId());
        }
        BigDecimal newAmount = members.stream()
            .map(m -> m.getAmount() == null ? BigDecimal.ZERO : MoneyUtil.round2(m.getAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (newAmount.signum() <= 0) {
            throw new ServiceException("增加角色人调整缺少新角色人业绩金额：adjustId={}", adjust.getId());
        }
        // 主单 targetAmount 即发起时新人金额合计；不一致说明数据异常，以快照合计为准并告警
        if (adjust.getTargetAmount() != null
            && MoneyUtil.round2(adjust.getTargetAmount()).compareTo(newAmount) != 0) {
            log.warn("[调整单-增加角色人] 主单目标金额({})与快照新人合计({})不一致，以快照为准：adjustId={}",
                adjust.getTargetAmount(), newAmount, adjust.getId());
        }

        List<PerformanceFact> facts = loadActiveFactsForAdjust(
            adjust.getPeriod(), adjust.getFactType(), adjust.getOrderNo(), adjust.getContractNo());
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
            throw new ServiceException("新角色人业绩金额合计超过合同当前业绩合计，会导致负数：adjustId={}, newAmount={}, total={}",
                adjust.getId(), newAmount, total);
        }
        // 每个新人都不能已在合同当前事实中，快照内也不允许重复
        Set<Long> memberEmpIds = new HashSet<>();
        for (AddMemberPayload.NewMember m : members) {
            if (m.getEmployeeId() == null) {
                throw new ServiceException("增加角色人快照缺少员工 ID：adjustId={}", adjust.getId());
            }
            if (!memberEmpIds.add(m.getEmployeeId())) {
                throw new ServiceException("增加角色人快照存在重复员工，终止执行：adjustId={}, employeeId={}",
                    adjust.getId(), m.getEmployeeId());
            }
            for (PerformanceFact f : facts) {
                if (m.getEmployeeId().equals(f.getEmployeeId())) {
                    throw new ServiceException("该员工已在此合同业绩中，不能重复增加：adjustId={}, employeeId={}",
                        adjust.getId(), m.getEmployeeId());
                }
            }
        }

        // 指定值模式（2026-09-28 可编辑表格）：既有行按 payload.detailTargets 精确 supersede，
        // 再逐条插入新人事实行；Σtargets + 未指定行 + 新人合计 = 合同总额（不变）
        if (payload != null && payload.getDetailTargets() != null && !payload.getDetailTargets().isEmpty()) {
            executeAddMemberByTargets(adjust, facts, payload, members, newAmount, operatorId);
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
            List<PerformanceFact> currentFacts = loadActiveFactsForAdjust(
                adjust.getPeriod(), adjust.getFactType(), adjust.getOrderNo(), adjust.getContractNo());
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

        // 3. 新事实：按快照逐个新角色人插入，period/签约日跟随合同既有事实（结佣按签约月），来源=手工
        for (AddMemberPayload.NewMember member : members) {
            factMapper.insert(buildNewMemberFact(facts.get(0), adjust, member));
        }
        log.info("[调整单-增加角色人] 执行完成：adjustId={}, contractNo={}, memberCount={}, newAmount={}, 指定扣除={}",
            adjust.getId(), adjust.getContractNo(), members.size(), newAmount, deductSum);
    }

    /**
     * 业绩冲正执行（MANUAL_OFFSET）：审批通过后按 payload 中的冲正明细清单，
     * 循环创建 MANUAL 事实（正数补录/负数冲正）。既有行按 payload 携带的 factId
     * 精确复制对应源事实（门店/费用项/占比等与弹窗所见一致），新增行回退合同
     * 既有 ACTIVE 事实模板；人员/金额/占比按冲正明细覆盖。
     */
    private void executeManualOffsetAdjust(PerformanceAdjust adjust, Long operatorId) {
        List<ManualOffsetItem> items = JsonUtils.parseArray(adjust.getPayloadJson(), ManualOffsetItem.class);
        if (items == null || items.isEmpty()) {
            throw new ServiceException("冲正明细为空，无法执行：adjustId={}", adjust.getId());
        }
        // 按 contractNo 跨期查 ACTIVE PERF_EXPECT 事实：既是默认模板，也用于按 factId 精确匹配
        List<PerformanceFact> templates = loadActiveFactsForAdjust(
            null, FACT_TYPE_EXPECT, adjust.getOrderNo(), adjust.getContractNo());
        if (templates == null || templates.isEmpty()) {
            throw new ServiceException("合同下未找到有效业绩事实：contractNo={}", adjust.getContractNo());
        }
        Map<Long, PerformanceFact> templateById = templates.stream()
            .collect(Collectors.toMap(PerformanceFact::getId, f -> f, (a, b) -> a));
        // （工号,角色类型）兜底匹配：前端 Long 精度丢失致 factId 失效时，仍能复制到正确源事实，
        // 避免回退 templates.get(0) 把首行 source_key/基础字段错套到其他角色人
        Map<String, PerformanceFact> templateByCodeRole = new HashMap<>();
        for (PerformanceFact f : templates) {
            if (StringUtils.isNotBlank(f.getEmployeeExternalCode()) && StringUtils.isNotBlank(f.getRoleType())) {
                templateByCodeRole.putIfAbsent(
                    f.getEmployeeExternalCode().trim() + "|" + f.getRoleType().trim(), f);
            }
        }
        PerformanceFact defaultTemplate = templates.get(0);
        String targetPeriod = adjust.getPeriod();
        java.time.LocalDate periodStart = YearMonth.parse(targetPeriod).atDay(1);
        long tsBase = System.currentTimeMillis();
        // 批量查员工主数据（工号/部门），冲正记录的人员字段按冲正明细覆盖，
        // 不沿用模板事实的人员信息（模板可能属于其他角色人）
        List<Long> employeeIds = items.stream()
            .map(ManualOffsetItem::getEmployeeId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Map<String, Object>> empMap = adjustMapper.employeeNames(employeeIds).stream()
            .collect(Collectors.toMap(m -> ((Number) m.get("employeeId")).longValue(), m -> m, (a, b) -> a));
        // 工号兜底：历史 payload 的 employeeId 可能已被 JS 取整（id 查不到时按工号补全姓名/部门）
        List<String> employeeCodes = items.stream()
            .map(ManualOffsetItem::getEmployeeCode).filter(StringUtils::isNotBlank)
            .map(String::trim).distinct().toList();
        Map<String, Map<String, Object>> empByCode = adjustMapper.employeesByCodes(employeeCodes).stream()
            .collect(Collectors.toMap(m -> String.valueOf(m.get("employeeCode")), m -> m, (a, b) -> a));
        int count = 0;
        List<OffsetLinkRow> linkRows = new ArrayList<>();
        for (ManualOffsetItem item : items) {
            // 既有行按 factId 精确复制 → （工号,角色）匹配 → 最后才回退默认模板
            PerformanceFact template = item.getFactId() == null ? null : templateById.get(item.getFactId());
            if (template == null && StringUtils.isNotBlank(item.getEmployeeCode())
                && StringUtils.isNotBlank(item.getRoleType())) {
                template = templateByCodeRole.get(item.getEmployeeCode().trim() + "|" + item.getRoleType().trim());
            }
            if (template == null) {
                template = defaultTemplate;
            }
            PerformanceFact fact = copyFactBase(template);
            fact.setFactStatus(FactStatus.ACTIVE);
            fact.setSource(PerformanceSource.MANUAL);
            fact.setSourceKey(template.getSourceKey() + "|MANUAL-OFFSET-" + tsBase + "-" + count);
            fact.setPeriod(targetPeriod);
            fact.setBusinessDate(periodStart.atStartOfDay());
            fact.setEffectiveDate(periodStart);
            fact.setBatchId(null);
            fact.setPerformanceAmount(item.getAmount());
            // 人员信息按冲正明细覆盖（工号取保存快照、缺失回退员工主数据；部门取员工主数据）
            fact.setEmployeeId(item.getEmployeeId());
            Map<String, Object> emp = item.getEmployeeId() == null ? null : empMap.get(item.getEmployeeId());
            if (emp == null && StringUtils.isNotBlank(item.getEmployeeCode())) {
                emp = empByCode.get(item.getEmployeeCode().trim());
            }
            if (StringUtils.isNotBlank(item.getEmployeeCode())) {
                fact.setEmployeeExternalCode(item.getEmployeeCode().trim());
            } else if (emp != null) {
                fact.setEmployeeExternalCode(toStringOrNull(emp.get("employeeCode")));
            }
            if (emp != null) {
                // 以员工主数据为准回填真实员工 ID / 部门（防止历史快照 employeeId 已被取整）
                fact.setEmployeeId(((Number) emp.get("employeeId")).longValue());
                Object deptId = emp.get("deptId");
                if (deptId != null) {
                    fact.setDeptId(((Number) deptId).longValue());
                }
            }
            fact.setRoleType(item.getRoleType());
            fact.setRoleName(item.getRoleName());
            if (item.getShareRatio() != null) {
                fact.setShareRatio(item.getShareRatio());
            }
            fact.setOperatorId(operatorId);
            factMapper.insert(fact);
            // 实收联动明细：人员/角色/费用项/金额与刚写入的新签事实同口径
            OffsetLinkRow linkRow = new OffsetLinkRow();
            linkRow.employeeId = fact.getEmployeeId();
            linkRow.employeeCode = fact.getEmployeeExternalCode();
            linkRow.deptId = fact.getDeptId();
            linkRow.roleType = item.getRoleType();
            linkRow.feeItem = template.getFeeItem();
            linkRow.shareRatio = fact.getShareRatio();
            linkRow.amount = item.getAmount() == null ? BigDecimal.ZERO : MoneyUtil.round2(item.getAmount());
            // 冲正弹窗新增的角色人行（无源事实）→ 实收侧无同行时需新建，口径同「增加角色人」
            linkRow.newMember = item.getFactId() == null;
            linkRows.add(linkRow);
            count++;
        }
        // 实收联动：与新签调整同款「按（员工+角色+费用项）同额联动」，冲正后实收合计与新签自动对齐
        linkReceivedForManualOffset(adjust, linkRows, operatorId);
        log.info("[调整单-业绩冲正] 执行完成：adjustId={}, contractNo={}, period={}, 记录数={}",
            adjust.getId(), adjust.getContractNo(), targetPeriod, count);
    }

    /** 冲正联动实收的明细行（已按员工主数据回填 ID/部门）。 */
    private static final class OffsetLinkRow {
        private Long employeeId;
        private String employeeCode;
        private Long deptId;
        private String roleType;
        private String feeItem;
        private BigDecimal shareRatio;
        private BigDecimal amount;
        /** 是否为冲正弹窗新增的角色人行（源事实为空） */
        private boolean newMember;
    }

    /**
     * 业绩冲正实收联动：口径与新签调整 {@link #linkReceivedForAdjust} 完全一致——
     * 按（员工 + 角色 + 费用项键）把冲正金额同额落到实收明细上（supersede 冲销链，正负均可）。
     * <ul>
     *   <li>命中同（员工+角色）实收行：按占比分摊后逐行调整；</li>
     *   <li>未命中角色行但该员工实收侧有其它行：退化为按该员工实收行等比分摊；</li>
     *   <li>新增角色人行（冲正弹窗添加的人）：实收侧无同行则新建实收行；</li>
     *   <li>既有人员在实收侧完全不存在：不凭空建行，仅告警（与调整口径一致）；</li>
     *   <li>合同整体无实收：按新签镜像建行，并直建一张审批通过的实收审批单。</li>
     * </ul>
     * 端口缺失时跳过。
     */
    private void linkReceivedForManualOffset(PerformanceAdjust adjust, List<OffsetLinkRow> linkRows,
                                              Long operatorId) {
        ReceivedRealFactPort port = receivedRealPort();
        if (port == null) {
            log.warn("[冲正单-实收联动] 实收端口缺失，跳过联动：adjustId={}", adjust.getId());
            return;
        }
        if (linkRows == null || linkRows.isEmpty()) {
            return;
        }
        String period = adjust.getPeriod();
        String orderNo = adjust.getOrderNo();
        String contractNo = adjust.getContractNo();
        String bizType = adjust.getBizType();

        List<PerformanceFactSummaryDTO> received = port.findActiveByContract(null, orderNo, contractNo);
        boolean hasAnyReceived = received != null && !received.isEmpty();
        BigDecimal originalReceivedTotal = BigDecimal.ZERO;
        BigDecimal appliedDelta = BigDecimal.ZERO;
        boolean linkedTouched = false;
        Map<Long, Map<String, List<PerformanceFactSummaryDTO>>> receivedByEmpRole = new HashMap<>();
        Long templateDetailId = null;
        if (received != null) {
            for (PerformanceFactSummaryDTO r : received) {
                if (r.getAmount() != null) {
                    originalReceivedTotal = originalReceivedTotal.add(r.getAmount());
                }
                if (r.getEmployeeId() == null || r.getFactId() == null) {
                    continue;
                }
                receivedByEmpRole
                    .computeIfAbsent(r.getEmployeeId(), k -> new HashMap<>())
                    .computeIfAbsent(normalizeRole(r.getRoleType()) + feeKeyOf(r.getFeeItem()), k -> new ArrayList<>())
                    .add(r);
                if (templateDetailId == null) {
                    templateDetailId = r.getFactId();
                }
            }
        }
        // 同一（员工+角色+费用项）多行合并为一次联动，避免后续行基于已变动的实收金额重复分摊
        Map<String, OffsetLinkRow> merged = new LinkedHashMap<>();
        for (OffsetLinkRow row : linkRows) {
            if (row.employeeId == null || MoneyUtil.isZero(row.amount)) {
                continue;
            }
            String roleKey = normalizeRole(row.roleType) + feeKeyOf(row.feeItem);
            String key = row.employeeId + "|" + roleKey;
            OffsetLinkRow exist = merged.get(key);
            if (exist == null) {
                exist = new OffsetLinkRow();
                exist.employeeId = row.employeeId;
                exist.employeeCode = row.employeeCode;
                exist.deptId = row.deptId;
                exist.roleType = row.roleType;
                exist.feeItem = row.feeItem;
                exist.shareRatio = row.shareRatio;
                exist.amount = BigDecimal.ZERO;
                exist.newMember = row.newMember;
                merged.put(key, exist);
            }
            exist.newMember = exist.newMember || row.newMember;
            exist.amount = MoneyUtil.round2(exist.amount.add(row.amount));
        }

        for (OffsetLinkRow row : merged.values()) {
            BigDecimal delta = MoneyUtil.round2(row.amount);
            if (MoneyUtil.isZero(delta)) {
                continue;
            }
            String roleKey = normalizeRole(row.roleType) + feeKeyOf(row.feeItem);
            Map<String, List<PerformanceFactSummaryDTO>> empReceived = receivedByEmpRole.get(row.employeeId);
            List<PerformanceFactSummaryDTO> matched = empReceived == null ? null : empReceived.get(roleKey);
            if (matched != null && !matched.isEmpty()) {
                adjustReceivedRows(port, matched, delta, adjust.getId(), operatorId);
                appliedDelta = appliedDelta.add(delta);
                linkedTouched = true;
                log.info("[冲正单-实收联动] adjustId={}, employeeId={}, role={}, 同额={}, 实收命中 {} 行联动",
                    adjust.getId(), row.employeeId, roleKey, delta, matched.size());
                continue;
            }
            if (!row.newMember && empReceived != null && !empReceived.isEmpty()) {
                // 角色口径不一致：退化为按该员工实收行等比分摊（不新建行）
                List<PerformanceFactSummaryDTO> all = empReceived.values().stream()
                    .flatMap(List::stream).toList();
                adjustReceivedRows(port, all, delta, adjust.getId(), operatorId);
                appliedDelta = appliedDelta.add(delta);
                linkedTouched = true;
                log.warn("[冲正单-实收联动] adjustId={}, employeeId={}, 角色={} 实收无同行，退化为按该员工 {} 行等比分摊",
                    adjust.getId(), row.employeeId, roleKey, all.size());
                continue;
            }
            if (hasAnyReceived && !row.newMember) {
                log.warn("[冲正单-实收联动] adjustId={}, employeeId={}, 角色={}, delta={} 在实收侧不存在，"
                    + "不凭空建实收行", adjust.getId(), row.employeeId, roleKey, delta);
                continue;
            }
            // 新增角色人 / 合同整体无实收：新建实收行
            String roleArg = StringUtils.isBlank(row.roleType) ? null : row.roleType;
            if (hasAnyReceived && templateDetailId != null) {
                port.addMemberDetail(templateDetailId, row.employeeId, row.employeeCode, row.deptId,
                    roleArg, delta, row.shareRatio, operatorId, adjust.getId());
            } else {
                port.createDetailFromContract(orderNo, contractNo, period, bizType,
                    row.employeeId, row.employeeCode, row.deptId, roleArg, delta, row.shareRatio,
                    operatorId, adjust.getId());
            }
            appliedDelta = appliedDelta.add(delta);
            linkedTouched = true;
            log.info("[冲正单-实收联动] 实收新建角色行：adjustId={}, employeeId={}, role={}, amount={}",
                adjust.getId(), row.employeeId, roleArg, delta);
        }

        if (!hasAnyReceived) {
            ReceivedApplyPort applyPort = receivedApplyPort();
            if (applyPort != null) {
                int newApplies = applyPort.autoCreateApprovedForAdjust(period, orderNo, contractNo,
                    operatorId, adjust.getId());
                if (newApplies > 0) {
                    log.info("[冲正单-实收联动] 无实收合同已直建审批通过实收审批单：adjustId={}, contractNo={}, 新建={}",
                        adjust.getId(), contractNo, newApplies);
                }
            } else {
                log.warn("[冲正单-实收联动] 实收审批单端口缺失，跳过直建审批单：adjustId={}", adjust.getId());
            }
        }

        if (linkedTouched) {
            recordLinkedReceivedAdjustMirror(adjust, bizType, originalReceivedTotal, appliedDelta, operatorId);
        }
    }

    /**
     * 增加角色人·指定值模式执行：既有行按 payload.detailTargets 精确 supersede
     * （金额 + 可选角色占比），再逐条插入新角色人事实行。
     * <p>
     * 完整性校验：Σ指定行目标 + 未指定行当前金额 + 全部新人金额 = 执行时合同既有行合计
     * （合同总额不变）；执行时合计被其他调整单抢先执行而变化时拒绝执行并留痕。
     */
    private void executeAddMemberByTargets(PerformanceAdjust adjust, List<PerformanceFact> facts,
                                           AddMemberPayload payload,
                                           List<AddMemberPayload.NewMember> members, BigDecimal newAmount,
                                           Long operatorId) {
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
        // 完整性校验：执行时 Σ指定行目标 + 未指定行现值 + 新人金额合计 必须等于发起时快照的调整后总额
        // （payload.afterTotal；2026-09-29 前旧单该字段为空，回退为执行时合计=总额不变语义）。
        // 执行时合同被其他调整单抢先改动会导致等式不成立，拒绝执行并留痕，避免金额错乱。
        BigDecimal expectedAfterTotal = payload.getAfterTotal() != null
            ? MoneyUtil.round2(payload.getAfterTotal()) : MoneyUtil.round2(total);
        BigDecimal actualAfterTotal = MoneyUtil.round2(coveredSum.add(uncoveredSum).add(newAmount));
        if (actualAfterTotal.compareTo(expectedAfterTotal) != 0) {
            throw new ServiceException("执行失败：执行时合同业绩合计已变化（既有行指定值{} + 未指定行{} + 新人合计{} "
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

        for (AddMemberPayload.NewMember member : members) {
            factMapper.insert(buildNewMemberFact(facts.get(0), adjust, member));
        }
        log.info("[调整单-增加角色人] 执行完成（指定值模式）：adjustId={}, contractNo={}, memberCount={}, "
                + "newAmount={}, 执行时合同合计={}",
            adjust.getId(), adjust.getContractNo(), members.size(), MoneyUtil.round2(newAmount),
            MoneyUtil.round2(total));
    }

    /**
     * 构建新角色人事实：以合同首条事实为模板复制合同/期间/金额口径等基础字段，
     * 人员信息覆盖为该新角色人，来源=MANUAL、批次/归一化记录引用置空（不归属于任何导入批次）。
     * sourceKey 对齐导入格式（orderNo|contractNo|角色人系统号|费用项|角色类型），
     * 角色人系统号用 MANUAL-{adjustNo}-{employeeId} 虚拟值：既避开导入事实的幂等键，
     * 又保证一张单多个新人（含角色类型相同）时 sourceKey 互不冲突；
     * 仍携带 MANUAL-ADJ 标记，业绩查询的「新签调整/新增角色人」识别口径不变。
     */
    private PerformanceFact buildNewMemberFact(PerformanceFact template, PerformanceAdjust adjust,
                                               AddMemberPayload.NewMember member) {
        PerformanceFact newFact = copyFactBase(template);
        newFact.setEmployeeId(member.getEmployeeId());
        newFact.setEmployeeExternalCode(member.getEmployeeCode());
        newFact.setRoleType(member.getRoleType());
        newFact.setRoleName(member.getRoleType());
        if (member.getDeptId() != null) {
            newFact.setDeptId(member.getDeptId());
        }
        // 新角色人业绩比例：发起时显式指定则落库，未指定保持 null（不设置占比）
        if (member.getNewShareRatio() != null) {
            newFact.setShareRatio(member.getNewShareRatio());
        }
        newFact.setPerformanceAmount(MoneyUtil.round2(
            member.getAmount() == null ? BigDecimal.ZERO : member.getAmount()));
        newFact.setSource(PerformanceSource.MANUAL);
        newFact.setBatchId(null);
        newFact.setNormalizedRecordId(null);
        newFact.setSourceKey(StringUtils.defaultString(template.getOrderNo()) + "|"
            + StringUtils.defaultString(template.getContractNo()) + "|MANUAL-" + adjust.getAdjustNo()
            + "-" + member.getEmployeeId() + "|" + StringUtils.defaultString(template.getFeeItem()) + "|"
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
        // 业绩冲正：冲正期间可能无事实，跨期查合同合计
        if (AdjustType.MANUAL_OFFSET.getCode().equals(dto.getAdjustType())) {
            period = null;
        }
        if (SCOPE_CONTRACT.equals(scope)) {
            // 同合同号挂多订单时按订单号+合同号双键汇总，避免跨订单混排虚增合同总额
            BigDecimal total = adjustMapper.selectContractTotalAmount(period, factType, dto.getOrderNo(), dto.getContractNo());
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

    /**
     * 认领当前节点待办（拥有总监角色但不在授权快照时，如事后补赋总监角色）。
     */
    public boolean claim(Long id) {
        return approvalPort.claim(BizType.PERF_ADJUST, id);
    }
}
