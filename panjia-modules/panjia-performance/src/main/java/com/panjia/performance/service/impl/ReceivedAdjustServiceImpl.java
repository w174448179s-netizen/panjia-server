package com.panjia.performance.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.common.util.DeptScopeUtils;
import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ApprovalStartCmd;
import com.panjia.contracts.port.CommissionGatePort;
import com.panjia.contracts.port.ReceivedRealFactPort;
import com.panjia.performance.domain.AdjustStatus;
import com.panjia.performance.domain.AdjustType;
import com.panjia.performance.domain.ReceivedAdjust;
import com.panjia.performance.domain.bo.AddMemberItemBo;
import com.panjia.performance.domain.bo.AddMemberPayload;
import com.panjia.performance.domain.bo.AdjustDetailTargetBo;
import com.panjia.performance.domain.bo.ReceivedAdjustBo;
import com.panjia.performance.domain.bo.ReceivedAdjustCreateBo;
import com.panjia.performance.domain.vo.AdjustDetailVo;
import com.panjia.performance.domain.vo.AdjustFactDetailVo;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.mapper.ReceivedAdjustMapper;
import com.panjia.performance.service.IPeriodCloseService;
import com.panjia.performance.service.IReceivedAdjustService;
import com.panjia.performance.util.MoneyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.enums.BusinessStatusEnum;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.DeptService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * 实收调整单服务实现（pj_perf_received_adjust）。
 * <p>
 * 对合同实收业绩（PERF_REAL，落表 pj_received_detail）做合同级金额调整：
 * 录入调整后实收合计，按当前实收金额占比等比分摊到各明细行（尾差补最大行），
 * 审批走 flowCode=perf_received_adjust，通过后经 {@link CommissionPerformanceQueryPortShim}
 * 同源端口（performanceQueryPort）按快照 before+delta 绝对值逐条 supersede 实收明细。
 * <p>
 * 2026-10-12 由新签调整（pj_perf_adjust，adjust_type=RECEIVED_AMOUNT）物理拆出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceivedAdjustServiceImpl implements IReceivedAdjustService {

    /** 调整单号前缀（与新签调整同规则；分表后唯一索引各自维护，不冲突） */
    private static final String ADJUST_NO_PREFIX = "ADJ";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 实收事实口径 */
    private static final String FACT_TYPE_REAL = "PERF_REAL";

    private static final String WF_STATUS_FINISH = "finish";
    private static final String WF_STATUS_BACK = "back";
    private static final String WF_STATUS_INVALID = "invalid";
    private static final String WF_STATUS_TERMINATION = "termination";
    private static final String WF_STATUS_CANCEL = "cancel";

    private final ReceivedAdjustMapper receivedAdjustMapper;
    private final PerformanceFactMapper factMapper;
    /** 实收事实跨域读写端口（PERF_REAL 唯一入口，禁止直连 pj_received_* 表） */
    private final com.panjia.contracts.port.CommissionPerformanceQueryPort performanceQueryPort;
    private final ApprovalPort approvalPort;
    private final IPeriodCloseService periodCloseService;
    private final DeptService deptService;
    /** 结佣闸门（实现 bean 在 panjia-commission，缺失时跳过校验） */
    private final ObjectProvider<CommissionGatePort> commissionGatePortProvider;
    /**
     * 实收事实写入口（实现在 panjia-received；三类型指定值/加人执行用）。
     * ObjectProvider 惰性获取，避免模块启动期的强依赖顺序问题。
     */
    private final ObjectProvider<ReceivedRealFactPort> receivedRealFactPortProvider;

    private ReceivedRealFactPort receivedRealPort() {
        ReceivedRealFactPort port = receivedRealFactPortProvider.getIfAvailable();
        if (port == null) {
            throw new ServiceException("实收事实写入端口未装配，无法执行实收调整");
        }
        return port;
    }

    /** 解析实收调整类型：仅允许 AMOUNT/DEDUCT/INCREASE，空值与历史单按 AMOUNT */
    private AdjustType parseReceivedAdjustType(String code) {
        AdjustType type = AdjustType.fromCode(code);
        if (type == null) {
            return AdjustType.AMOUNT;
        }
        if (type != AdjustType.AMOUNT && type != AdjustType.DEDUCT && type != AdjustType.INCREASE) {
            throw new ServiceException("实收调整不支持该类型：{}", code);
        }
        return type;
    }

    // ==================== 列表 ====================

    @Override
    public PageResult<ReceivedAdjust> list(ReceivedAdjustBo query, PageQuery pageQuery) {
        if (query != null) {
            query.setDeptId(DeptScopeUtils.enforceSelfDeptScope(
                query.getDeptId(), deptService::selectDeptAndChildById, "实收调整"));
        }
        LambdaQueryWrapper<ReceivedAdjust> wrapper = buildQueryWrapper(query);
        wrapper.orderByDesc(ReceivedAdjust::getCreateTime);

        Page<ReceivedAdjust> page = receivedAdjustMapper.selectPage(pageQuery.build(), wrapper);
        List<ReceivedAdjust> records = page.getRecords();
        fillDeptNames(records);
        return PageResult.build(records, page.getTotal());
    }

    @Override
    public List<String> distinctBizTypes(ReceivedAdjustBo query) {
        Long deptId = query == null ? null : DeptScopeUtils.enforceSelfDeptScope(
            query.getDeptId(), deptService::selectDeptAndChildById, "实收调整");
        return receivedAdjustMapper.selectDistinctBizTypes(
            query == null ? null : query.getPeriod(), deptId, query == null ? null : query.getEmployeeId());
    }

    private LambdaQueryWrapper<ReceivedAdjust> buildQueryWrapper(ReceivedAdjustBo query) {
        LambdaQueryWrapper<ReceivedAdjust> wrapper = new LambdaQueryWrapper<>();
        if (query == null) {
            return wrapper;
        }
        wrapper.eq(StringUtils.isNotBlank(query.getPeriod()), ReceivedAdjust::getPeriod, query.getPeriod());
        wrapper.eq(StringUtils.isNotBlank(query.getStatus()),
            ReceivedAdjust::getStatus, AdjustStatus.fromCode(query.getStatus()));
        wrapper.eq(query.getEmployeeId() != null, ReceivedAdjust::getEmployeeId, query.getEmployeeId());
        wrapper.eq(StringUtils.isNotBlank(query.getBizType()), ReceivedAdjust::getBizType, query.getBizType());
        wrapper.eq(StringUtils.isNotBlank(query.getAdjustType()),
            ReceivedAdjust::getAdjustType, query.getAdjustType());
        if (query.getDeptId() != null) {
            Long deptId = query.getDeptId();
            wrapper.apply("dept_id = {0} OR dept_id IN (SELECT sd.dept_id FROM sys_dept sd"
                + " WHERE sd.ancestors LIKE CONCAT('%', {0}, '%'))", deptId);
        }
        if (StringUtils.isNotBlank(query.getKeyword())) {
            String kw = query.getKeyword().trim();
            wrapper.and(w -> w.like(ReceivedAdjust::getAdjustNo, kw)
                .or().like(ReceivedAdjust::getContractNo, kw)
                .or().like(ReceivedAdjust::getOrderNo, kw));
        }
        return wrapper;
    }

    /** 批量回填部门名称（sys_dept，一次 IN 查询） */
    private void fillDeptNames(List<ReceivedAdjust> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Set<Long> deptIds = records.stream()
            .map(ReceivedAdjust::getDeptId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> deptNameMap = new HashMap<>();
        for (Map<String, Object> row : receivedAdjustMapper.deptNames(new ArrayList<>(deptIds))) {
            deptNameMap.put(((Number) row.get("deptId")).longValue(), String.valueOf(row.get("deptName")));
        }
        for (ReceivedAdjust r : records) {
            if (r.getDeptId() != null) {
                r.setDeptName(deptNameMap.get(r.getDeptId()));
            }
        }
    }

    // ==================== 发起 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReceivedAdjust create(ReceivedAdjustCreateBo dto, Long applicantId) {
        if (StringUtils.isBlank(dto.getContractNo()) || StringUtils.isBlank(dto.getPeriod())) {
            throw new ServiceException("实收调整缺少合同号或实收期间");
        }

        // 1. 校验 + 等比分摊预演快照（同时回填 targetAmount/payloadJson/employeeId/deptId/bizType）
        //    2026-10-14 三类型重构：带明细指定值时走与新签调整一致的指定值/加人链路（prepareTargets），
        //    不带明细时保留旧等比分摊链路（兼容）。
        AdjustType adjustType = parseReceivedAdjustType(dto.getAdjustType());
        boolean targetsMode = dto.getDetailTargets() != null && !dto.getDetailTargets().isEmpty();
        BigDecimal originalAmt = targetsMode ? prepareTargets(dto, adjustType) : prepare(dto);

        // 2. 结佣已审批锁定则禁止发起实收调整（与新签调整同一闸门，保护已锁定结佣数据）
        CommissionGatePort gate = commissionGatePortProvider.getIfAvailable();
        if (gate != null && gate.isCommissionLocked(dto.getPeriod(), dto.getContractNo())) {
            throw new ServiceException("合同 " + dto.getContractNo() + " " + dto.getPeriod()
                + " 月结佣已审批通过并锁定，不能发起实收调整；如需调整请先作废结佣单");
        }

        // 3. 在途互斥：同合同同期间存在审批中的实收调整单时拒绝（串行调整，防快照失效）
        Long inFlight = receivedAdjustMapper.selectCount(new LambdaQueryWrapper<ReceivedAdjust>()
            .eq(ReceivedAdjust::getContractNo, dto.getContractNo())
            .eq(ReceivedAdjust::getPeriod, dto.getPeriod())
            .in(ReceivedAdjust::getStatus, AdjustStatus.SUBMITTED, AdjustStatus.APPROVED));
        if (inFlight != null && inFlight > 0) {
            throw new ServiceException("该合同该实收期间存在审批中的实收调整单，请待其审批完成或撤回后再发起");
        }

        // 4. 落库
        ReceivedAdjust adjust = new ReceivedAdjust();
        adjust.setAdjustNo(generateAdjustNo());
        adjust.setPeriod(dto.getPeriod());
        adjust.setEmployeeId(dto.getEmployeeId());
        adjust.setDeptId(dto.getDeptId());
        adjust.setContractNo(dto.getContractNo());
        adjust.setOrderNo(dto.getOrderNo());
        adjust.setBizType(dto.getBizType());
        adjust.setAdjustType(adjustType.getCode());
        adjust.setPayloadJson(dto.getPayloadJson());
        adjust.setTargetAmount(dto.getTargetAmount());
        adjust.setOriginalAmount(originalAmt);
        adjust.setReason(StringUtils.isBlank(dto.getReason()) ? "实收调整" : dto.getReason());
        adjust.setStatus(AdjustStatus.SUBMITTED);
        adjust.setApplicantId(applicantId);
        receivedAdjustMapper.insert(adjust);

        // 5. 发起审批（bizType=PERF_RECEIVED_ADJUST → flowCode=perf_received_adjust；bizId=调整单ID）
        ApprovalStartCmd cmd = buildStartCmd(adjust);
        boolean started;
        try {
            started = approvalPort.startAndCompleteFirst(BizType.PERF_RECEIVED_ADJUST, adjust.getId(), cmd);
        } catch (Exception e) {
            log.error("[实收调整] 审批流程发起异常：adjustId={}", adjust.getId(), e);
            throw new ServiceException("实收调整审批流程发起失败：{}", e.getMessage());
        }
        if (!started) {
            throw new ServiceException("实收调整审批流程发起失败");
        }

        // 6. 回填流程实例 ID（失败不阻断，回调按 businessId 路由）
        try {
            Long instanceId = approvalPort.instanceId(BizType.PERF_RECEIVED_ADJUST, adjust.getId());
            if (instanceId != null) {
                adjust.setProcessInstanceId(String.valueOf(instanceId));
                receivedAdjustMapper.updateById(adjust);
            }
        } catch (Exception e) {
            log.warn("[实收调整] 流程实例ID回填失败：adjustId={}", adjust.getId(), e);
        }

        log.info("[实收调整] 创建并提交审批成功：adjustId={}, adjustNo={}, contractNo={}, period={}, applicantId={}",
            adjust.getId(), adjust.getAdjustNo(), adjust.getContractNo(), adjust.getPeriod(), applicantId);
        return adjust;
    }

    /**
     * 创建准备：装载 PERF_REAL 实收明细 → 全部 APPROVED 校验 → 原合计/目标合计/变动额校验
     * → 按当前实收金额占比等比分摊（尾差补最大行）→ 构建快照 payload → 回填 dto。
     *
     * @return 原实收合计
     */
    private BigDecimal prepare(ReceivedAdjustCreateBo dto) {
        List<PerformanceFactSummaryDTO> facts = performanceQueryPort.findActiveByBizKey(
            dto.getPeriod(), dto.getOrderNo(), dto.getContractNo(), FACT_TYPE_REAL);
        if (facts == null || facts.isEmpty()) {
            throw new ServiceException("该合同该期间无有效实收明细，无法发起实收调整：contractNo={}, period={}",
                dto.getContractNo(), dto.getPeriod());
        }
        for (PerformanceFactSummaryDTO f : facts) {
            if (!"APPROVED".equals(f.getReceivedStatus())) {
                throw new ServiceException("存在未审批通过的实收明细，禁止实收调整：factId={}, receivedStatus={}",
                    f.getFactId(), f.getReceivedStatus());
            }
        }
        BigDecimal originalAmt = MoneyUtil.round2(facts.stream()
            .map(f -> f.getAmount() == null ? BigDecimal.ZERO : f.getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal targetAmt = dto.getTargetAmount() != null
            ? MoneyUtil.round2(dto.getTargetAmount())
            : MoneyUtil.round2(originalAmt.add(
                dto.getDeltaAmount() == null ? BigDecimal.ZERO : dto.getDeltaAmount()));
        BigDecimal deltaTotal = MoneyUtil.round2(targetAmt.subtract(originalAmt));
        if (MoneyUtil.isZero(deltaTotal)) {
            throw new ServiceException("实收调整额为 0（正数=多收、负数=少收），无需发起调整");
        }
        if (targetAmt.signum() < 0) {
            throw new ServiceException("调整后实收合计不能为负：{}", targetAmt);
        }

        // 等比分摊预演
        List<BigDecimal> amounts = facts.stream()
            .map(f -> f.getAmount() == null ? BigDecimal.ZERO : f.getAmount())
            .toList();
        BigDecimal[] parts = MoneyUtil.allocateByAmount(amounts, deltaTotal);
        Set<Long> employeeIds = facts.stream()
            .map(PerformanceFactSummaryDTO::getEmployeeId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, String> nameMap = new HashMap<>();
        for (Map<String, Object> row : receivedAdjustMapper.employeeNames(new ArrayList<>(employeeIds))) {
            nameMap.put(((Number) row.get("employeeId")).longValue(), String.valueOf(row.get("employeeName")));
        }
        List<AddMemberPayload.Alloc> allocations = new ArrayList<>(facts.size());
        for (int i = 0; i < facts.size(); i++) {
            PerformanceFactSummaryDTO f = facts.get(i);
            AddMemberPayload.Alloc alloc = new AddMemberPayload.Alloc();
            alloc.setFactId(f.getFactId());
            alloc.setEmployeeId(f.getEmployeeId());
            alloc.setEmployeeName(f.getEmployeeId() != null ? nameMap.get(f.getEmployeeId()) : null);
            alloc.setBefore(f.getAmount() == null ? BigDecimal.ZERO : MoneyUtil.round2(f.getAmount()));
            alloc.setDelta(MoneyUtil.round2(parts[i]));
            allocations.add(alloc);
        }
        AddMemberPayload payload = new AddMemberPayload();
        payload.setContractTotal(originalAmt);
        payload.setAfterTotal(targetAmt);
        payload.setAllocations(allocations);
        dto.setPayloadJson(JsonUtils.toJsonString(payload));
        dto.setTargetAmount(targetAmt);

        // 员工/部门/业务类型从首条实收明细回填（列表展示/数据权限/号源口径）
        PerformanceFactSummaryDTO first = facts.get(0);
        if (dto.getEmployeeId() == null) {
            dto.setEmployeeId(first.getEmployeeId());
        }
        if (dto.getDeptId() == null || dto.getDeptId() <= 0) {
            dto.setDeptId(first.getDeptId());
        }
        if (StringUtils.isBlank(dto.getBizType())) {
            dto.setBizType(first.getBizType());
        }
        return originalAmt;
    }

    /**
     * 装载合同某实收期间全部有效 PERF_REAL 事实并校验（无有效明细/存在未审批实收均拒绝）。
     */
    private List<PerformanceFactSummaryDTO> loadApprovedReceivedFacts(ReceivedAdjustCreateBo dto) {
        List<PerformanceFactSummaryDTO> facts = performanceQueryPort.findActiveByBizKey(
            dto.getPeriod(), dto.getOrderNo(), dto.getContractNo(), FACT_TYPE_REAL);
        if (facts == null || facts.isEmpty()) {
            throw new ServiceException("该合同该期间无有效实收明细，无法发起实收调整：contractNo={}, period={}",
                dto.getContractNo(), dto.getPeriod());
        }
        for (PerformanceFactSummaryDTO f : facts) {
            if (!"APPROVED".equals(f.getReceivedStatus())) {
                throw new ServiceException("存在未审批通过的实收明细，禁止实收调整：factId={}, receivedStatus={}",
                    f.getFactId(), f.getReceivedStatus());
            }
        }
        return facts;
    }

    /**
     * 实收调整三类型指定值准备（对齐新签 prepareContractTargets）：
     * 明细行指定「调整后实收金额/角色占比」+ 可选新角色人，按类型校验调整后总额规则
     * （AMOUNT 不变 / DEDUCT 必须减少 / INCREASE 必须增加），生成 AddMemberPayload 快照回填 dto。
     *
     * @return 原实收合计
     */
    private BigDecimal prepareTargets(ReceivedAdjustCreateBo dto, AdjustType adjustType) {
        List<PerformanceFactSummaryDTO> facts = loadApprovedReceivedFacts(dto);
        Map<Long, PerformanceFactSummaryDTO> factById = facts.stream()
            .collect(Collectors.toMap(PerformanceFactSummaryDTO::getFactId, f -> f, (a, b) -> a));
        BigDecimal total = facts.stream()
            .map(f -> f.getAmount() == null ? BigDecimal.ZERO : f.getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 1. 明细指定值校验：行须归属本合同本期实收；占比不为负；实收行目标金额不为负
        List<AdjustDetailTargetBo> validTargets = new ArrayList<>();
        Map<Long, BigDecimal> targetAmountByFact = new HashMap<>();
        BigDecimal coveredSum = BigDecimal.ZERO;
        Set<Long> coveredIds = new HashSet<>();
        for (AdjustDetailTargetBo t : dto.getDetailTargets()) {
            if (t == null || t.getFactId() == null || t.getTargetAmount() == null) {
                continue;
            }
            PerformanceFactSummaryDTO fact = factById.get(t.getFactId());
            if (fact == null) {
                throw new ServiceException("指定调整行不在该合同本期实收明细中：factId={}", t.getFactId());
            }
            if (t.getShareRatio() != null && t.getShareRatio().signum() < 0) {
                throw new ServiceException("角色占比不能为负：factId={}", t.getFactId());
            }
            if (t.getTargetAmount().signum() < 0) {
                throw new ServiceException("调整后实收金额不能为负：员工={}，金额={}",
                    fact.getEmployeeName(), MoneyUtil.round2(t.getTargetAmount()));
            }
            validTargets.add(t);
            coveredIds.add(t.getFactId());
            targetAmountByFact.put(t.getFactId(), MoneyUtil.round2(t.getTargetAmount()));
            coveredSum = coveredSum.add(t.getTargetAmount());
        }
        BigDecimal uncoveredSum = facts.stream()
            .filter(f -> !coveredIds.contains(f.getFactId()))
            .map(f -> f.getAmount() == null ? BigDecimal.ZERO : f.getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 2. 新角色人清单：金额>0、占比不为负、单内判重、不得已在本期实收行中
        List<AddMemberItemBo> requestMembers = new ArrayList<>();
        if (dto.getNewMembers() != null) {
            for (AddMemberItemBo item : dto.getNewMembers()) {
                if (item != null && item.getEmployeeId() != null) {
                    requestMembers.add(item);
                }
            }
        }
        Set<Long> existingEmpIds = facts.stream()
            .map(PerformanceFactSummaryDTO::getEmployeeId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Set<Long> requestEmpIds = new HashSet<>();
        for (AddMemberItemBo m : requestMembers) {
            if (m.getAmount() == null || m.getAmount().signum() <= 0) {
                throw new ServiceException("新角色人实收金额缺失或不大于 0：employeeId={}", m.getEmployeeId());
            }
            if (m.getShareRatio() != null && m.getShareRatio().signum() < 0) {
                throw new ServiceException("新角色人角色占比不能为负：employeeId={}", m.getEmployeeId());
            }
            if (!requestEmpIds.add(m.getEmployeeId())) {
                throw new ServiceException("同一员工不能重复添加为新角色人：employeeId={}", m.getEmployeeId());
            }
            if (existingEmpIds.contains(m.getEmployeeId())) {
                throw new ServiceException("员工已在此合同本期实收中，请直接调整其实收金额：employeeId={}",
                    m.getEmployeeId());
            }
        }
        BigDecimal newX = requestMembers.stream()
            .map(m -> MoneyUtil.round2(m.getAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 3. 类型总额规则（与新签调整一致）
        BigDecimal originalAmt = MoneyUtil.round2(total);
        BigDecimal afterTotal = MoneyUtil.round2(coveredSum.add(uncoveredSum).add(newX));
        if (adjustType == AdjustType.AMOUNT && afterTotal.compareTo(originalAmt) != 0) {
            throw new ServiceException("业绩调整必须保证修改后实收总额不变（当前 {} → {}），请检查各行金额",
                originalAmt, afterTotal);
        }
        if (adjustType == AdjustType.DEDUCT && afterTotal.compareTo(originalAmt) >= 0) {
            throw new ServiceException("业绩扣减要求修改后实收总额小于原金额（当前 {} → {}）",
                originalAmt, afterTotal);
        }
        if (adjustType == AdjustType.INCREASE && afterTotal.compareTo(originalAmt) <= 0) {
            throw new ServiceException("业绩增加要求修改后实收总额大于原金额（当前 {} → {}）",
                originalAmt, afterTotal);
        }

        // 4. 分摊预演快照（before=当前金额、delta=目标-当前；未指定行 delta=0）
        Set<Long> employeeIds = facts.stream()
            .map(PerformanceFactSummaryDTO::getEmployeeId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, String> nameMap = new HashMap<>();
        for (Map<String, Object> row : receivedAdjustMapper.employeeNames(new ArrayList<>(employeeIds))) {
            nameMap.put(((Number) row.get("employeeId")).longValue(), String.valueOf(row.get("employeeName")));
        }
        List<AddMemberPayload.Alloc> allocations = new ArrayList<>(facts.size());
        for (PerformanceFactSummaryDTO f : facts) {
            AddMemberPayload.Alloc alloc = new AddMemberPayload.Alloc();
            alloc.setFactId(f.getFactId());
            alloc.setEmployeeId(f.getEmployeeId());
            alloc.setEmployeeName(f.getEmployeeId() != null ? nameMap.get(f.getEmployeeId()) : null);
            BigDecimal current = f.getAmount() == null ? BigDecimal.ZERO : MoneyUtil.round2(f.getAmount());
            alloc.setBefore(current);
            BigDecimal target = targetAmountByFact.get(f.getFactId());
            alloc.setDelta(target == null ? BigDecimal.ZERO : MoneyUtil.round2(target.subtract(current)));
            allocations.add(alloc);
        }

        // 5. 新角色人档案快照（一次 IN 批量查工号/姓名/部门）
        List<AddMemberPayload.NewMember> memberSnapshots = new ArrayList<>(requestMembers.size());
        if (!requestMembers.isEmpty()) {
            List<Long> memberIds = requestMembers.stream().map(AddMemberItemBo::getEmployeeId).toList();
            Map<Long, Map<String, Object>> empMap = new HashMap<>();
            for (Map<String, Object> row : receivedAdjustMapper.employeeNames(memberIds)) {
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
                        throw new ServiceException("员工档案未配置部门，无法确定实收归属部门：employeeId={}",
                            m.getEmployeeId());
                    }
                    m.setDeptId(((Number) empDeptId).longValue());
                }
            }
            List<Long> memberDeptIds = requestMembers.stream()
                .map(AddMemberItemBo::getDeptId).filter(Objects::nonNull).distinct().toList();
            Map<Long, String> deptNameMap = new HashMap<>();
            for (Map<String, Object> row : receivedAdjustMapper.deptNames(memberDeptIds)) {
                deptNameMap.put(((Number) row.get("deptId")).longValue(), String.valueOf(row.get("deptName")));
            }
            for (AddMemberItemBo m : requestMembers) {
                Map<String, Object> empRow = empMap.get(m.getEmployeeId());
                AddMemberPayload.NewMember snap = new AddMemberPayload.NewMember();
                snap.setEmployeeId(m.getEmployeeId());
                snap.setEmployeeCode(empRow.get("employeeCode") == null ? null
                    : String.valueOf(empRow.get("employeeCode")));
                snap.setEmployeeName(empRow.get("employeeName") == null ? null
                    : String.valueOf(empRow.get("employeeName")));
                snap.setDeptId(m.getDeptId());
                snap.setDeptName(deptNameMap.get(m.getDeptId()));
                snap.setRoleType(StringUtils.defaultIfBlank(m.getRoleType(), "合作人"));
                snap.setAmount(MoneyUtil.round2(m.getAmount()));
                snap.setNewShareRatio(m.getShareRatio() == null ? null : MoneyUtil.round6(m.getShareRatio()));
                memberSnapshots.add(snap);
            }
        }

        // 6. dto 回填 + payload 组装
        dto.setTargetAmount(afterTotal);
        AddMemberPayload payload = new AddMemberPayload();
        payload.setDetailTargets(validTargets);
        payload.setContractTotal(originalAmt);
        payload.setAfterTotal(afterTotal);
        payload.setAllocations(allocations);
        if (!memberSnapshots.isEmpty()) {
            payload.setMembers(memberSnapshots);
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
        dto.setPayloadJson(JsonUtils.toJsonString(payload));

        // 员工/部门/业务类型回填（列表展示/数据权限/号源口径）：有新人挂第一个新人，否则挂首条实收行
        PerformanceFactSummaryDTO first = facts.get(0);
        if (!memberSnapshots.isEmpty()) {
            dto.setEmployeeId(memberSnapshots.get(0).getEmployeeId());
            dto.setDeptId(memberSnapshots.get(0).getDeptId());
        } else {
            if (dto.getEmployeeId() == null) {
                dto.setEmployeeId(first.getEmployeeId());
            }
            if (dto.getDeptId() == null || dto.getDeptId() <= 0) {
                dto.setDeptId(first.getDeptId());
            }
        }
        if (StringUtils.isBlank(dto.getBizType())) {
            dto.setBizType(first.getBizType());
        }
        return originalAmt;
    }

    private ApprovalStartCmd buildStartCmd(ReceivedAdjust adjust) {
        AdjustType type = parseReceivedAdjustType(adjust.getAdjustType());
        StringBuilder title = new StringBuilder()
            .append("实收").append(type.getDesc())
            .append("｜合同").append(adjust.getContractNo())
            .append("｜账期").append(adjust.getPeriod())
            .append("｜调整后实收").append(adjust.getTargetAmount());
        AddMemberPayload payload = parsePayload(adjust.getPayloadJson());
        if (payload != null && !payload.effectiveMembers().isEmpty()) {
            String names = payload.effectiveMembers().stream()
                .map(AddMemberPayload.NewMember::getEmployeeName)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining(","));
            if (StringUtils.isNotBlank(names)) {
                title.append("｜新增角色人:").append(names);
            }
        }
        title.append("｜单号").append(adjust.getAdjustNo());
        ApprovalStartCmd cmd = ApprovalStartCmd.of(adjust.getAdjustNo(), title.toString());
        Map<String, Object> variables = new HashMap<>(2);
        variables.put("ignore", true);
        cmd.setVariables(variables);
        return cmd;
    }

    private String generateAdjustNo() {
        return ADJUST_NO_PREFIX + LocalDate.now().format(DATE_FORMATTER) + RandomUtil.randomNumbers(6);
    }

    // ==================== 详情 ====================

    @Override
    public AdjustDetailVo getDetail(Long id) {
        ReceivedAdjust adjust = receivedAdjustMapper.selectById(id);
        if (adjust == null) {
            return null;
        }
        // 工作流终态但业务单卡在 SUBMITTED 时自愈对齐
        syncStatusWithWorkflow(adjust);

        AdjustDetailVo dto = new AdjustDetailVo();
        org.springframework.beans.BeanUtils.copyProperties(adjust, dto);
        // 实收调整单固定口径：PERF_REAL；调整类型取单据真实值（历史单 null 按 AMOUNT）
        dto.setAdjustType(parseReceivedAdjustType(adjust.getAdjustType()));
        dto.setFactType(FACT_TYPE_REAL);

        // 合同信息（房源地址/订单号/签约时间）：新签事实通常与实收合同同号，按实收期间查不到则跨期回退
        java.util.Map<String, Object> contractInfo =
            factMapper.selectContractInfoByContractNo(adjust.getPeriod(), adjust.getOrderNo(), adjust.getContractNo());
        if (contractInfo == null) {
            // 实收期间与新签签约月可能不同，跨期（period=null）再查一次
            contractInfo =
                factMapper.selectContractInfoByContractNo(null, adjust.getOrderNo(), adjust.getContractNo());
        }
        if (contractInfo != null) {
            dto.setOrderNo(contractInfo.get("orderNo") == null ? dto.getOrderNo()
                : String.valueOf(contractInfo.get("orderNo")));
            if (contractInfo.get("propertyAddress") != null) {
                dto.setPropertyAddress(String.valueOf(contractInfo.get("propertyAddress")));
            }
            if (contractInfo.get("businessDate") != null) {
                dto.setBusinessDate(String.valueOf(contractInfo.get("businessDate")));
            }
        }

        fillDetailRows(adjust, dto);
        return dto;
    }

    /**
     * 实收调整明细回填：
     * <ul>
     *   <li>三类型指定值/加人单（payload 含 detailTargets 或 members）：未执行=预演（金额=现值、
     *       变动=快照 delta、新角色人追加 amount=null 的虚拟行）；已执行=反转语义
     *       （金额=快照 before、调整后=当前值、新角色人金额 0→X）。</li>
     *   <li>历史等比单：按 payload.allocations 快照回填 before/delta/after。</li>
     * </ul>
     */
    private void fillDetailRows(ReceivedAdjust adjust, AdjustDetailVo dto) {
        List<PerformanceFactSummaryDTO> facts = performanceQueryPort.findActiveByBizKey(
            adjust.getPeriod(), adjust.getOrderNo(), adjust.getContractNo(), FACT_TYPE_REAL);
        AddMemberPayload payload = parsePayload(adjust.getPayloadJson());
        boolean targetsMode = payload != null && (
            (payload.getDetailTargets() != null && !payload.getDetailTargets().isEmpty())
                || !payload.effectiveMembers().isEmpty());
        List<AdjustFactDetailVo> rows = targetsMode
            ? buildTargetDetailRows(adjust, facts, payload)
            : buildLegacyDetailRows(facts, payload);
        dto.setDetails(rows);
        dto.setDetailCount(rows.size());
        // 应收合计位=调整前实收、实收合计位=调整后实收（面板按 isReceivedAdjust 口径读这两个字段）
        dto.setExpectedTotal(adjust.getOriginalAmount());
        dto.setReceivedTotal(adjust.getTargetAmount());
        dto.setTargetAmount(adjust.getTargetAmount());
        if (payload != null && payload.getAfterTotal() != null) {
            dto.setAfterTotalAmount(payload.getAfterTotal());
        }
    }

    /** 历史等比单明细：PERF_REAL 实收行按 allocations 快照回填（amount=before、delta、after=before+delta） */
    private List<AdjustFactDetailVo> buildLegacyDetailRows(List<PerformanceFactSummaryDTO> facts,
                                                           AddMemberPayload payload) {
        Map<Long, AddMemberPayload.Alloc> allocByFact = new HashMap<>();
        Map<Long, AddMemberPayload.Alloc> allocByEmployee = new HashMap<>();
        if (payload != null && payload.getAllocations() != null) {
            for (AddMemberPayload.Alloc a : payload.getAllocations()) {
                if (a == null) {
                    continue;
                }
                if (a.getFactId() != null) {
                    allocByFact.put(a.getFactId(), a);
                }
                if (a.getEmployeeId() != null) {
                    allocByEmployee.putIfAbsent(a.getEmployeeId(), a);
                }
            }
        }
        List<AdjustFactDetailVo> rows = new ArrayList<>(facts.size());
        for (PerformanceFactSummaryDTO f : facts) {
            AdjustFactDetailVo row = new AdjustFactDetailVo();
            row.setFactId(f.getFactId());
            row.setEmployeeId(f.getEmployeeId());
            row.setEmployeeCode(f.getEmployeeCode());
            row.setEmployeeName(f.getEmployeeName());
            row.setDeptPath(f.getDeptName());
            row.setRoleType(f.getRoleType());
            row.setRoleName(f.getRoleName());
            row.setShareRatio(f.getShareRatio());
            AddMemberPayload.Alloc alloc = allocByFact.get(f.getFactId());
            if (alloc == null && f.getEmployeeId() != null) {
                alloc = allocByEmployee.get(f.getEmployeeId());
            }
            BigDecimal current = f.getAmount() == null ? BigDecimal.ZERO : f.getAmount();
            BigDecimal amount = alloc != null && alloc.getBefore() != null ? alloc.getBefore() : current;
            BigDecimal delta = alloc != null && alloc.getDelta() != null ? alloc.getDelta() : BigDecimal.ZERO;
            row.setAmount(MoneyUtil.round2(amount));
            row.setDeltaAmount(MoneyUtil.round2(delta));
            row.setAfterAmount(MoneyUtil.round2(amount.add(delta)));
            row.setTarget(delta.signum() != 0);
            rows.add(row);
        }
        return rows;
    }

    /** 三类型指定值/加人单明细（对齐新签 applyAddMemberDetail 口径） */
    private List<AdjustFactDetailVo> buildTargetDetailRows(ReceivedAdjust adjust,
                                                           List<PerformanceFactSummaryDTO> facts,
                                                           AddMemberPayload payload) {
        boolean alreadyExecuted = adjust.getStatus() == AdjustStatus.EXECUTED;
        List<AddMemberPayload.NewMember> members = payload.effectiveMembers();

        Map<Long, AddMemberPayload.Alloc> allocByFact = new HashMap<>();
        Map<Long, AddMemberPayload.Alloc> allocByEmployee = new HashMap<>();
        if (payload.getAllocations() != null) {
            for (AddMemberPayload.Alloc a : payload.getAllocations()) {
                if (a == null) {
                    continue;
                }
                if (a.getFactId() != null) {
                    allocByFact.put(a.getFactId(), a);
                }
                if (a.getEmployeeId() != null) {
                    allocByEmployee.putIfAbsent(a.getEmployeeId(), a);
                }
            }
        }

        List<AdjustFactDetailVo> rows = new ArrayList<>(facts.size() + members.size());
        for (PerformanceFactSummaryDTO f : facts) {
            AdjustFactDetailVo row = new AdjustFactDetailVo();
            row.setFactId(f.getFactId());
            row.setEmployeeId(f.getEmployeeId());
            row.setEmployeeCode(f.getEmployeeCode());
            row.setEmployeeName(f.getEmployeeName());
            row.setDeptPath(f.getDeptName());
            row.setRoleType(f.getRoleType());
            row.setRoleName(f.getRoleName());
            row.setShareRatio(f.getShareRatio());
            BigDecimal current = f.getAmount() == null ? BigDecimal.ZERO : f.getAmount();

            // 新角色人行（执行后已作为 ACTIVE 实收行查出）：原值 0 → 当前 X
            AddMemberPayload.NewMember member = matchMember(members, f.getEmployeeId(), f.getRoleType());
            if (member != null) {
                row.setAmount(BigDecimal.ZERO);
                row.setDeltaAmount(MoneyUtil.round2(current));
                row.setAfterAmount(MoneyUtil.round2(current));
                row.setTarget(true);
                rows.add(row);
                continue;
            }

            AddMemberPayload.Alloc alloc = allocByFact.get(f.getFactId());
            if (alloc == null && f.getEmployeeId() != null) {
                alloc = allocByEmployee.get(f.getEmployeeId());
            }
            if (alreadyExecuted) {
                BigDecimal before = alloc != null && alloc.getBefore() != null ? alloc.getBefore() : current;
                row.setAmount(MoneyUtil.round2(before));
                row.setDeltaAmount(MoneyUtil.round2(current.subtract(before)));
                row.setAfterAmount(MoneyUtil.round2(current));
                row.setTarget(true);
            } else {
                BigDecimal delta = alloc != null && alloc.getDelta() != null ? alloc.getDelta() : BigDecimal.ZERO;
                row.setAmount(MoneyUtil.round2(current));
                row.setDeltaAmount(MoneyUtil.round2(delta));
                row.setAfterAmount(MoneyUtil.round2(current.add(delta)));
                row.setTarget(delta.signum() != 0);
            }
            rows.add(row);
        }

        // 未执行：新角色人尚不在实收明细中，按快照追加虚拟行（amount=null、after=X）
        if (!alreadyExecuted) {
            for (AddMemberPayload.NewMember m : members) {
                BigDecimal memberAmount = MoneyUtil.round2(
                    m.getAmount() == null ? BigDecimal.ZERO : m.getAmount());
                AdjustFactDetailVo row = new AdjustFactDetailVo();
                row.setEmployeeId(m.getEmployeeId());
                row.setEmployeeCode(m.getEmployeeCode());
                row.setEmployeeName(m.getEmployeeName());
                row.setDeptPath(m.getDeptName());
                row.setRoleType(m.getRoleType());
                row.setRoleName(m.getRoleType());
                row.setShareRatio(m.getNewShareRatio());
                row.setAmount(null);
                row.setDeltaAmount(memberAmount);
                row.setAfterAmount(memberAmount);
                row.setTarget(true);
                rows.add(row);
            }
        }
        return rows;
    }

    private AddMemberPayload.NewMember matchMember(List<AddMemberPayload.NewMember> members,
                                                   Long employeeId, String roleType) {
        if (employeeId == null) {
            return null;
        }
        for (AddMemberPayload.NewMember m : members) {
            if (employeeId.equals(m.getEmployeeId())
                && (StringUtils.isBlank(m.getRoleType()) || m.getRoleType().equals(roleType))) {
                return m;
            }
        }
        return null;
    }

    private AddMemberPayload parsePayload(String payloadJson) {
        if (StringUtils.isBlank(payloadJson)) {
            return null;
        }
        try {
            return JsonUtils.parseObject(payloadJson, AddMemberPayload.class);
        } catch (Exception e) {
            log.warn("[实收调整] 分摊快照解析失败：payload={}", payloadJson, e);
            return null;
        }
    }

    // ==================== 撤回 / 工作流回调 / 执行 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id, Long operatorId) {
        ReceivedAdjust adjust = getAndCheck(id);
        if (adjust.getStatus() != AdjustStatus.SUBMITTED) {
            throw new ServiceException("仅审批中的实收调整单允许撤回，当前状态：{}",
                adjust.getStatus() == null ? "未知" : adjust.getStatus().getCode());
        }
        if (!Objects.equals(adjust.getApplicantId(), operatorId)) {
            throw new ServiceException("仅调整单发起人本人可撤回");
        }
        // 实例按 businessId 定位，bizType 仅语义占位；迁移单（原 perf_adjust 流程）同样可取消
        try {
            approvalPort.cancel(BizType.PERF_RECEIVED_ADJUST, id);
        } catch (Exception e) {
            log.error("[实收调整] 撤回审批流程失败：adjustId={}", id, e);
            throw new ServiceException("撤回审批流程失败：{}", e.getMessage());
        }
        adjust.setStatus(AdjustStatus.CANCELLED);
        adjust.setProcessInstanceId(null);
        adjust.setOperatorId(operatorId);
        receivedAdjustMapper.updateById(adjust);
        log.info("[实收调整] 申请人撤回：adjustId={}, adjustNo={}, operatorId={}",
            id, adjust.getAdjustNo(), operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleWorkflowEvent(Long adjustId, String status, String handler, String message) {
        ReceivedAdjust adjust = receivedAdjustMapper.selectById(adjustId);
        if (adjust == null) {
            log.warn("[实收调整工作流] 调整单不存在，忽略回调：adjustId={}, status={}", adjustId, status);
            return;
        }
        Long handlerId = parseHandlerId(handler);
        switch (status == null ? "" : status) {
            case WF_STATUS_FINISH -> {
                if (adjust.getStatus() == AdjustStatus.EXECUTED) {
                    log.info("[实收调整工作流] 已执行，幂等忽略 finish 回调：adjustId={}", adjustId);
                    return;
                }
                if (adjust.getStatus() != AdjustStatus.SUBMITTED && adjust.getStatus() != AdjustStatus.APPROVED) {
                    log.info("[实收调整工作流] 非提交/审批通过态，忽略 finish 回调：adjustId={}, current={}",
                        adjustId, adjust.getStatus());
                    return;
                }
                adjust.setStatus(AdjustStatus.APPROVED);
                adjust.setApproverId(handlerId);
                adjust.setApproveTime(LocalDateTime.now());
                receivedAdjustMapper.updateById(adjust);
                execute(adjustId, handlerId);
            }
            case WF_STATUS_BACK -> {
                if (adjust.getStatus() != AdjustStatus.SUBMITTED) {
                    log.info("[实收调整工作流] 非提交态，忽略驳回回调：adjustId={}", adjustId);
                    return;
                }
                adjust.setStatus(AdjustStatus.REJECTED);
                adjust.setApproverId(handlerId);
                adjust.setApproveTime(LocalDateTime.now());
                receivedAdjustMapper.updateById(adjust);
                log.info("[实收调整工作流] 总监驳回，置 REJECTED：adjustId={}, message={}", adjustId, message);
            }
            case WF_STATUS_INVALID, WF_STATUS_TERMINATION -> {
                if (adjust.getStatus() != AdjustStatus.SUBMITTED) {
                    log.info("[实收调整工作流] 非提交态，忽略作废/终止回调：adjustId={}", adjustId);
                    return;
                }
                adjust.setStatus(AdjustStatus.REJECTED);
                adjust.setApproverId(handlerId);
                adjust.setApproveTime(LocalDateTime.now());
                receivedAdjustMapper.updateById(adjust);
                log.info("[实收调整工作流] 流程作废/终止，置 REJECTED：adjustId={}, message={}", adjustId, message);
            }
            case WF_STATUS_CANCEL -> {
                if (adjust.getStatus() != AdjustStatus.SUBMITTED) {
                    log.info("[实收调整工作流] 非提交态，忽略撤销回调：adjustId={}", adjustId);
                    return;
                }
                adjust.setStatus(AdjustStatus.CANCELLED);
                adjust.setOperatorId(handlerId);
                receivedAdjustMapper.updateById(adjust);
                log.info("[实收调整工作流] 流程撤销，置 CANCELLED：adjustId={}, message={}", adjustId, message);
            }
            default -> log.info("[实收调整工作流] 无需处理的状态，忽略：adjustId={}, status={}", adjustId, status);
        }
    }

    /**
     * 执行实收调整：
     * <ul>
     *   <li>三类型指定值/加人单（payload.detailTargets 非空）：既有行按指定金额/占比精确 supersede，
     *       新角色人插入新 ACTIVE 实收行，执行前做 afterTotal 完整性校验；</li>
     *   <li>历史等比单：按 payload 分摊快照逐条 supersede（绝对值 before+delta），
     *       Σ(before+delta) 必须等于单据目标金额。</li>
     * </ul>
     */
    private void execute(Long adjustId, Long operatorId) {
        ReceivedAdjust adjust = getAndCheck(adjustId);
        // 封账校验（实收期间）
        if (periodCloseService.isClosed(adjust.getPeriod())) {
            throw new ServiceException("期间已封账，禁止执行实收调整：实收期间=" + adjust.getPeriod());
        }
        AddMemberPayload payload = parsePayload(adjust.getPayloadJson());
        if (payload == null) {
            throw new ServiceException("实收调整分摊快照缺失，无法执行：adjustId={}", adjustId);
        }
        if (payload.getDetailTargets() != null && !payload.getDetailTargets().isEmpty()) {
            executeTargets(adjust, payload, operatorId);
            return;
        }
        if (payload.getAllocations() == null || payload.getAllocations().isEmpty()) {
            throw new ServiceException("实收调整分摊快照缺失，无法执行：adjustId={}", adjustId);
        }
        if (adjust.getTargetAmount() == null) {
            throw new ServiceException("实收调整缺少目标金额：adjustId={}", adjustId);
        }
        BigDecimal afterTotal = BigDecimal.ZERO;
        for (AddMemberPayload.Alloc alloc : payload.getAllocations()) {
            if (alloc == null || alloc.getFactId() == null) {
                continue;
            }
            BigDecimal before = alloc.getBefore() == null ? BigDecimal.ZERO : alloc.getBefore();
            BigDecimal delta = alloc.getDelta() == null ? BigDecimal.ZERO : alloc.getDelta();
            afterTotal = afterTotal.add(MoneyUtil.round2(before.add(delta)));
            if (MoneyUtil.isZero(delta)) {
                continue;
            }
            PerformanceFactSummaryDTO fact = performanceQueryPort.getByFactId(alloc.getFactId());
            if (fact == null || !"ACTIVE".equals(fact.getFactStatus())) {
                throw new ServiceException("执行失败：实收明细不存在或已非有效状态，请撤回该调整单后重新发起："
                    + "adjustId={}, factId={}", adjustId, alloc.getFactId());
            }
            if (StringUtils.isNotBlank(adjust.getContractNo())
                && !adjust.getContractNo().equals(fact.getContractNo())
                && !adjust.getContractNo().equals(fact.getOrderNo())) {
                throw new ServiceException("执行失败：实收明细 {} 已不属于合同 {}，请撤回该调整单后重新发起：adjustId={}",
                    alloc.getFactId(), adjust.getContractNo(), adjustId);
            }
            performanceQueryPort.adjustFactAmount(alloc.getFactId(),
                MoneyUtil.round2(before.add(delta)), operatorId, adjust.getId());
        }
        if (MoneyUtil.round2(afterTotal).compareTo(MoneyUtil.round2(adjust.getTargetAmount())) != 0) {
            throw new ServiceException("执行失败：Σ(调整前+分摊额)({}) 与目标金额({})不一致，"
                    + "为避免金额错乱终止执行：adjustId={}",
                MoneyUtil.round2(afterTotal), MoneyUtil.round2(adjust.getTargetAmount()), adjustId);
        }
        markExecuted(adjust, operatorId);
    }

    /**
     * 三类型指定值执行（对齐新签 executeContractTargetsAdjust）：
     * 既有实收行按快照指定「调整后金额/角色占比」精确 supersede（仅落变化行），
     * 新角色人以合同首条实收行为模板插入新 ACTIVE 行；执行时合同被其他单据抢先改动会导致
     * Σ指定行目标 + 未指定行现值 + 新人合计 ≠ 快照 afterTotal，拒绝执行防金额错乱。
     * 审批期间快照行被其他链路 supersede/红冲（当前 ACTIVE 集合查不到）同样拒绝，提示撤回重发。
     */
    private void executeTargets(ReceivedAdjust adjust, AddMemberPayload payload, Long operatorId) {
        if (adjust.getTargetAmount() == null) {
            throw new ServiceException("实收调整缺少目标金额：adjustId={}", adjust.getId());
        }
        ReceivedRealFactPort port = receivedRealPort();
        List<PerformanceFactSummaryDTO> facts = performanceQueryPort.findActiveByBizKey(
            adjust.getPeriod(), adjust.getOrderNo(), adjust.getContractNo(), FACT_TYPE_REAL);
        if (facts == null || facts.isEmpty()) {
            throw new ServiceException("执行失败：合同该期间已无有效实收明细，请撤回该调整单后重新发起：adjustId={}",
                adjust.getId());
        }
        Map<Long, PerformanceFactSummaryDTO> factById = facts.stream()
            .collect(Collectors.toMap(PerformanceFactSummaryDTO::getFactId, f -> f, (a, b) -> a));

        List<AddMemberPayload.NewMember> members = payload.effectiveMembers();
        Set<Long> memberEmpIds = new HashSet<>();
        for (AddMemberPayload.NewMember m : members) {
            if (m.getEmployeeId() == null) {
                throw new ServiceException("新角色人快照缺少员工 ID：adjustId={}", adjust.getId());
            }
            if (!memberEmpIds.add(m.getEmployeeId())) {
                throw new ServiceException("新角色人快照存在重复员工，终止执行：adjustId={}, employeeId={}",
                    adjust.getId(), m.getEmployeeId());
            }
            for (PerformanceFactSummaryDTO f : facts) {
                if (m.getEmployeeId().equals(f.getEmployeeId())) {
                    throw new ServiceException("该员工已在此合同本期实收中，不能重复增加：adjustId={}, employeeId={}",
                        adjust.getId(), m.getEmployeeId());
                }
            }
        }

        BigDecimal coveredSum = BigDecimal.ZERO;
        Set<Long> coveredIds = new HashSet<>();
        for (AdjustDetailTargetBo t : payload.getDetailTargets()) {
            if (t == null || t.getFactId() == null || t.getTargetAmount() == null) {
                continue;
            }
            if (factById.get(t.getFactId()) == null) {
                throw new ServiceException("执行失败：实收明细 {} 已不存在或已被其他调整冲销，"
                    + "请撤回该调整单后重新发起：adjustId={}", t.getFactId(), adjust.getId());
            }
            if (!coveredIds.add(t.getFactId())) {
                throw new ServiceException("执行失败：快照存在重复指定行，请撤回该调整单后重新发起：adjustId={}",
                    adjust.getId());
            }
            coveredSum = coveredSum.add(t.getTargetAmount());
        }
        BigDecimal uncoveredSum = facts.stream()
            .filter(f -> !coveredIds.contains(f.getFactId()))
            .map(f -> f.getAmount() == null ? BigDecimal.ZERO : f.getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal newAmount = members.stream()
            .map(m -> m.getAmount() == null ? BigDecimal.ZERO : MoneyUtil.round2(m.getAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expectedAfterTotal = payload.getAfterTotal() != null
            ? MoneyUtil.round2(payload.getAfterTotal()) : MoneyUtil.round2(adjust.getTargetAmount());
        BigDecimal actualAfterTotal = MoneyUtil.round2(coveredSum.add(uncoveredSum).add(newAmount));
        if (actualAfterTotal.compareTo(expectedAfterTotal) != 0) {
            throw new ServiceException("执行失败：执行时实收合计已变化（指定行{} + 未指定行{} + 新人合计{} "
                    + "≠ 发起时调整后总额{}），为避免金额错乱终止执行：adjustId={}",
                MoneyUtil.round2(coveredSum), MoneyUtil.round2(uncoveredSum), MoneyUtil.round2(newAmount),
                expectedAfterTotal, adjust.getId());
        }

        // 既有行：金额或占比有变化才 supersede
        for (AdjustDetailTargetBo t : payload.getDetailTargets()) {
            if (t == null || t.getFactId() == null || t.getTargetAmount() == null) {
                continue;
            }
            PerformanceFactSummaryDTO old = factById.get(t.getFactId());
            BigDecimal current = old.getAmount() == null ? BigDecimal.ZERO : old.getAmount();
            BigDecimal target = MoneyUtil.round2(t.getTargetAmount());
            boolean amountChanged = target.compareTo(MoneyUtil.round2(current)) != 0;
            boolean ratioChanged = t.getShareRatio() != null
                && (old.getShareRatio() == null || t.getShareRatio().compareTo(old.getShareRatio()) != 0);
            if (!amountChanged && !ratioChanged) {
                continue;
            }
            Long newDetailId = port.adjustDetailAmount(t.getFactId(), target,
                ratioChanged ? t.getShareRatio() : null, operatorId, adjust.getId());
            if (newDetailId == null) {
                throw new ServiceException("执行失败：实收明细 supersede 未生成新行：factId={}, adjustId={}",
                    t.getFactId(), adjust.getId());
            }
        }

        // 新角色人：以首条实收行为模板插入新 ACTIVE 行（合同聚合由实收域自动重算）
        Long templateDetailId = facts.get(0).getFactId();
        for (AddMemberPayload.NewMember m : members) {
            Long newDetailId = port.addMemberDetail(templateDetailId, m.getEmployeeId(), m.getEmployeeCode(),
                m.getDeptId(), m.getRoleType(), m.getAmount(), m.getNewShareRatio(), operatorId, adjust.getId());
            if (newDetailId == null) {
                throw new ServiceException("执行失败：新角色人实收行插入失败（模板明细不存在）：employeeId={}, adjustId={}",
                    m.getEmployeeId(), adjust.getId());
            }
        }
        markExecuted(adjust, operatorId);
        log.info("[实收调整-三类型] 执行完成：adjustId={}, contractNo={}, period={}, memberCount={}, newAmount={}",
            adjust.getId(), adjust.getContractNo(), adjust.getPeriod(), members.size(),
            MoneyUtil.round2(newAmount));
    }

    private void markExecuted(ReceivedAdjust adjust, Long operatorId) {
        adjust.setStatus(AdjustStatus.EXECUTED);
        adjust.setOperatorId(operatorId);
        adjust.setExecuteTime(LocalDateTime.now());
        receivedAdjustMapper.updateById(adjust);
        log.info("[实收调整] 执行完成：adjustId={}, contractNo={}, period={}, 目标金额={}",
            adjust.getId(), adjust.getContractNo(), adjust.getPeriod(), MoneyUtil.round2(adjust.getTargetAmount()));
    }

    @Override
    public boolean hasInFlight(String contractNo, String period) {
        if (StringUtils.isBlank(contractNo) || StringUtils.isBlank(period)) {
            return false;
        }
        Long count = receivedAdjustMapper.selectCount(new LambdaQueryWrapper<ReceivedAdjust>()
            .eq(ReceivedAdjust::getContractNo, contractNo)
            .eq(ReceivedAdjust::getPeriod, period)
            .in(ReceivedAdjust::getStatus, AdjustStatus.SUBMITTED, AdjustStatus.APPROVED));
        return count != null && count > 0;
    }

    // ==================== 内部工具 ====================

    private ReceivedAdjust getAndCheck(Long id) {
        ReceivedAdjust adjust = receivedAdjustMapper.selectById(id);
        if (adjust == null) {
            throw new ServiceException("实收调整单不存在：id={}", id);
        }
        return adjust;
    }

    private Long parseHandlerId(String handler) {
        if (StringUtils.isBlank(handler)) {
            return null;
        }
        try {
            return Long.parseLong(handler.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 状态自愈：工作流已终态但单据卡在 SUBMITTED 时按工作流状态对齐（详情加载时调用）。
     * 实例按 businessId 定位，迁移单（原 perf_adjust 流程）同样可查。
     */
    private void syncStatusWithWorkflow(ReceivedAdjust adjust) {
        if (adjust == null || adjust.getStatus() != AdjustStatus.SUBMITTED
            || StringUtils.isBlank(adjust.getProcessInstanceId())) {
            return;
        }
        String wfStatus;
        try {
            wfStatus = approvalPort.businessStatus(BizType.PERF_RECEIVED_ADJUST, adjust.getId());
        } catch (Exception e) {
            log.warn("[实收调整状态自愈] 查询工作流状态失败，跳过：adjustId={}", adjust.getId(), e);
            return;
        }
        if (StringUtils.isBlank(wfStatus)
            || BusinessStatusEnum.WAITING.getStatus().equals(wfStatus)
            || BusinessStatusEnum.DRAFT.getStatus().equals(wfStatus)) {
            return;
        }
        log.info("[实收调整状态自愈] 发现状态不一致，开始修复：adjustId={}, wfStatus={}",
            adjust.getId(), wfStatus);
        switch (wfStatus) {
            case "cancel" -> {
                adjust.setStatus(AdjustStatus.CANCELLED);
                receivedAdjustMapper.updateById(adjust);
            }
            case "finish" -> {
                adjust.setStatus(AdjustStatus.APPROVED);
                receivedAdjustMapper.updateById(adjust);
                try {
                    execute(adjust.getId(), null);
                } catch (Exception e) {
                    log.error("[实收调整状态自愈] 补执行失败：adjustId={}", adjust.getId(), e);
                    throw e;
                }
            }
            case "back", "invalid", "termination" -> {
                adjust.setStatus(AdjustStatus.REJECTED);
                receivedAdjustMapper.updateById(adjust);
            }
            default -> log.info("[实收调整状态自愈] 未知工作流状态，不处理：adjustId={}, wfStatus={}",
                adjust.getId(), wfStatus);
        }
    }

    /**
     * 认领当前节点待办（拥有财务/总监角色但不在授权快照时）。
     */
    public boolean claim(Long id) {
        return approvalPort.claim(BizType.PERF_RECEIVED_ADJUST, id);
    }
}
