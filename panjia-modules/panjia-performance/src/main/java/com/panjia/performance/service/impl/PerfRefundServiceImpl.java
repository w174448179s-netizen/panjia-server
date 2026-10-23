package com.panjia.performance.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.common.util.DeptScopeUtils;
import com.panjia.contracts.constant.BizType;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.ApprovalStartCmd;
import com.panjia.contracts.port.CommissionRefundPort;
import com.panjia.contracts.port.ReceivedRealFactPort;
import com.panjia.performance.domain.AdjustStatus;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerfRefund;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.PerformanceSource;
import com.panjia.performance.domain.ReversalType;
import com.panjia.performance.domain.bo.PerfRefundBo;
import com.panjia.performance.domain.bo.PerfRefundCreateBo;
import com.panjia.performance.mapper.PerfRefundMapper;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.service.IPerfRefundService;
import com.panjia.performance.service.IPeriodCloseService;
import com.panjia.performance.util.MoneyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.DeptService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 退单服务实现（pj_perf_refund）。
 * <p>
 * 执行口径：审批通过后，对合同下全部 ACTIVE 正新签事实逐条镜像生成
 * {@link ReversalType#REDINK_REFUND} 负事实（归属月=退单月），
 * 对合同下全部 ACTIVE 实收明细逐条生成同额负实收明细（归属月=退单月）。
 * 原事实/原明细一律不动，负流水由结佣与算薪引擎按月自然读取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerfRefundServiceImpl implements IPerfRefundService {

    private static final String REFUND_NO_PREFIX = "TD";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String WF_STATUS_FINISH = "finish";
    private static final String WF_STATUS_BACK = "back";
    private static final String WF_STATUS_INVALID = "invalid";
    private static final String WF_STATUS_TERMINATION = "termination";
    private static final String WF_STATUS_CANCEL = "cancel";

    private final PerfRefundMapper refundMapper;
    private final PerformanceFactMapper factMapper;
    private final ApprovalPort approvalPort;
    private final IPeriodCloseService periodCloseService;
    private final DeptService deptService;
    /** 实收事实写入口（实现在 panjia-received；缺失时跳过实收联动） */
    private final ObjectProvider<ReceivedRealFactPort> receivedRealFactPortProvider;
    /** 结佣冲减入口（实现在 panjia-commission；缺失时跳过结佣联动） */
    private final ObjectProvider<CommissionRefundPort> commissionRefundPortProvider;

    // ==================== 列表 ====================

    @Override
    public PageResult<PerfRefund> list(PerfRefundBo query, PageQuery pageQuery) {
        if (query != null) {
            query.setDeptId(DeptScopeUtils.enforceSelfDeptScope(
                query.getDeptId(), deptService::selectDeptAndChildById, "退单"));
        }
        LambdaQueryWrapper<PerfRefund> wrapper = new LambdaQueryWrapper<>();
        if (query != null) {
            wrapper.eq(StringUtils.isNotBlank(query.getRefundPeriod()),
                PerfRefund::getRefundPeriod, query.getRefundPeriod());
            wrapper.eq(StringUtils.isNotBlank(query.getStatus()),
                PerfRefund::getStatus, AdjustStatus.fromCode(query.getStatus()));
            wrapper.eq(query.getEmployeeId() != null, PerfRefund::getEmployeeId, query.getEmployeeId());
            wrapper.eq(StringUtils.isNotBlank(query.getBizType()), PerfRefund::getBizType, query.getBizType());
            if (query.getDeptId() != null) {
                Long deptId = query.getDeptId();
                wrapper.apply("dept_id = {0} OR dept_id IN (SELECT sd.dept_id FROM sys_dept sd"
                    + " WHERE sd.ancestors LIKE CONCAT('%', {0}, '%'))", deptId);
            }
            if (StringUtils.isNotBlank(query.getKeyword())) {
                String kw = query.getKeyword().trim();
                wrapper.and(w -> w.like(PerfRefund::getRefundNo, kw)
                    .or().like(PerfRefund::getContractNo, kw)
                    .or().like(PerfRefund::getOrderNo, kw));
            }
        }
        wrapper.orderByDesc(PerfRefund::getCreateTime);
        Page<PerfRefund> page = refundMapper.selectPage(pageQuery.build(), wrapper);
        fillDeptNames(page.getRecords());
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public PerfRefund getById(Long id) {
        PerfRefund refund = requireRefund(id);
        fillDeptNames(List.of(refund));
        return refund;
    }

    @Override
    public Map<String, Object> summary(String refundPeriod) {
        Map<String, Object> row = refundMapper.sumExecutedAmounts(refundPeriod);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("expectAmount", row == null ? BigDecimal.ZERO : row.get("expectAmount"));
        result.put("realAmount", row == null ? BigDecimal.ZERO : row.get("realAmount"));
        result.put("commissionAmount", row == null ? BigDecimal.ZERO : row.get("commissionAmount"));
        result.put("refundCount", row == null ? 0 : row.get("refundCount"));
        return result;
    }

    @Override
    public Map<String, String> refundedPeriods(List<String> contractNos) {
        if (contractNos == null || contractNos.isEmpty()) {
            return Map.of();
        }
        List<String> keys = contractNos.stream()
            .filter(StringUtils::isNotBlank).distinct().collect(Collectors.toList());
        if (keys.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (Map<String, Object> row : refundMapper.selectRefundedPeriods(keys)) {
            result.put(String.valueOf(row.get("contractNo")), String.valueOf(row.get("refundPeriod")));
        }
        return result;
    }

    /** 批量回填部门名称（sys_dept，一次 IN 查询） */
    private void fillDeptNames(List<PerfRefund> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Set<Long> deptIds = records.stream()
            .map(PerfRefund::getDeptId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (deptIds.isEmpty()) {
            return;
        }
        Map<Long, String> nameMap = new HashMap<>();
        for (Map<String, Object> row : refundMapper.deptNames(new ArrayList<>(deptIds))) {
            nameMap.put(((Number) row.get("deptId")).longValue(), String.valueOf(row.get("deptName")));
        }
        for (PerfRefund r : records) {
            if (r.getDeptId() != null) {
                r.setDeptName(nameMap.get(r.getDeptId()));
            }
        }
    }

    // ==================== 预览 ====================

    @Override
    public Map<String, Object> preview(String contractNo, String orderNo, String refundPeriod) {
        if (StringUtils.isBlank(contractNo)) {
            throw new ServiceException("退单缺少合同号");
        }
        List<PerformanceFact> facts = loadRefundableExpectFacts(contractNo, orderNo);
        List<PerformanceFactSummaryDTO> reals = loadRefundableRealFacts(contractNo, orderNo);

        BigDecimal expectAmount = MoneyUtil.round2(facts.stream()
            .map(f -> f.getPerformanceAmount().negate())
            .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal realAmount = MoneyUtil.round2(reals.stream()
            .map(r -> r.getAmount().negate())
            .reduce(BigDecimal.ZERO, BigDecimal::add));
        // 冲减结佣：仅已结佣（APPROVED 结佣明细）部分需要显式生成负明细；
        // 未结佣部分由后续发起结佣读取负新签事实自然抵扣
        CommissionRefundPort commissionPort = commissionRefundPortProvider.getIfAvailable();
        BigDecimal commissionAmount = MoneyUtil.round2(commissionPort == null
            ? BigDecimal.ZERO
            : commissionPort.sumSettledExpectAmount(contractNo, orderNo).negate());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractNo", contractNo);
        result.put("orderNo", orderNo);
        result.put("refundPeriod", refundPeriod);
        result.put("expectAmount", expectAmount);
        result.put("realAmount", realAmount);
        result.put("commissionAmount", commissionAmount);
        result.put("factCount", facts.size());
        result.put("realCount", reals.size());
        result.put("bizType", facts.isEmpty() ? null : facts.get(0).getBizType());
        result.put("blocked", blockReason(refundPeriod));

        // 实收明细（退单月在退单月生成同额负明细）
        List<Map<String, Object>> realLines = new ArrayList<>();
        for (PerformanceFactSummaryDTO r : reals) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("detailId", r.getFactId());
            // 原归属月（原事实所在月，负数落账月见 refundPeriod）
            line.put("period", r.getPeriod());
            line.put("refundPeriod", refundPeriod);
            line.put("employeeId", r.getEmployeeId());
            line.put("employeeCode", r.getEmployeeCode());
            line.put("roleType", r.getRoleType());
            line.put("roleName", r.getRoleName());
            line.put("amount", MoneyUtil.round2(r.getAmount().negate()));
            realLines.add(line);
        }
        result.put("realDetails", realLines);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (PerformanceFact f : facts) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("factId", f.getId());
            line.put("factType", f.getFactType() == null ? null : f.getFactType().getCode());
            // 原归属月（原事实所在月，负数落账月见 refundPeriod）
            line.put("period", f.getPeriod());
            line.put("refundPeriod", refundPeriod);
            line.put("employeeId", f.getEmployeeId());
            line.put("employeeCode", f.getEmployeeExternalCode());
            line.put("roleType", f.getRoleType());
            line.put("roleName", f.getRoleName());
            line.put("shareRatio", f.getShareRatio());
            line.put("amount", MoneyUtil.round2(f.getPerformanceAmount().negate()));
            lines.add(line);
        }
        result.put("details", lines);
        return result;
    }

    // ==================== 发起 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PerfRefund create(PerfRefundCreateBo dto, Long applicantId) {
        String contractNo = dto.getContractNo().trim();
        String refundPeriod = dto.getRefundPeriod().trim();

        // 1. 重复退单拦截：同合同已有生效中的退单
        if (refundMapper.countEffectiveByContract(contractNo) > 0) {
            throw new ServiceException("该合同已存在退单（审批中或已执行），不可重复退单");
        }

        // 2. 装载可退事实并校验封账
        List<PerformanceFact> facts = loadRefundableExpectFacts(contractNo, dto.getOrderNo());
        if (facts.isEmpty()) {
            throw new ServiceException("该合同无可退的新签业绩（已退单或不存在正业绩事实）");
        }
        String blocked = blockReason(refundPeriod);
        if (blocked != null) {
            throw new ServiceException(blocked);
        }

        List<PerformanceFactSummaryDTO> reals = loadRefundableRealFacts(contractNo, dto.getOrderNo());

        // 3. 组装退单
        PerformanceFact first = facts.get(0);
        PerfRefund refund = new PerfRefund();
        refund.setRefundNo(REFUND_NO_PREFIX + LocalDateTime.now().format(DATE_FORMATTER)
            + RandomUtil.randomNumbers(6));
        refund.setContractNo(contractNo);
        refund.setOrderNo(StringUtils.isNotBlank(dto.getOrderNo())
            ? dto.getOrderNo().trim() : first.getOrderNo());
        refund.setRefundPeriod(refundPeriod);
        refund.setBizType(first.getBizType());
        refund.setEmployeeId(first.getEmployeeId());
        refund.setDeptId(first.getDeptId());
        refund.setExpectAmount(MoneyUtil.round2(facts.stream()
            .map(f -> f.getPerformanceAmount().negate())
            .reduce(BigDecimal.ZERO, BigDecimal::add)));
        refund.setRealAmount(MoneyUtil.round2(reals.stream()
            .map(r -> r.getAmount().negate())
            .reduce(BigDecimal.ZERO, BigDecimal::add)));
        refund.setReason(dto.getReason());
        refund.setStatus(AdjustStatus.SUBMITTED);
        refund.setApplicantId(applicantId);
        refund.setPayloadJson(JsonUtils.toJsonString(buildPayload(facts)));
        refundMapper.insert(refund);

        // 4. 启动审批（bizType=PERF_REFUND → flowCode=perf_refund；bizId=退单 ID）
        ApprovalStartCmd cmd = ApprovalStartCmd.of(refund.getRefundNo(),
            "退单申请｜合同 " + contractNo + " · " + refundPeriod);
        boolean started;
        try {
            started = approvalPort.startAndCompleteFirst(BizType.PERF_REFUND, refund.getId(), cmd);
        } catch (Exception e) {
            log.error("[退单] 审批流程发起异常：refundId={}", refund.getId(), e);
            throw new ServiceException("退单审批流程发起失败：{}", e.getMessage());
        }
        if (!started) {
            throw new ServiceException("退单审批流程发起失败");
        }

        // 5. 回填流程实例 ID（失败不阻断）
        try {
            Long instanceId = approvalPort.instanceId(BizType.PERF_REFUND, refund.getId());
            if (instanceId != null) {
                refund.setProcessInstanceId(String.valueOf(instanceId));
                refundMapper.updateById(refund);
            }
        } catch (Exception e) {
            log.warn("[退单] 流程实例ID回填失败：refundId={}", refund.getId(), e);
        }

        log.info("[退单] 创建并提交审批成功：refundId={}, refundNo={}, contractNo={}, refundPeriod={}, expectAmount={}, realAmount={}",
            refund.getId(), refund.getRefundNo(), contractNo, refundPeriod,
            refund.getExpectAmount(), refund.getRealAmount());
        return refund;
    }

    /**
     * 可退新签事实：合同（+订单）下 ACTIVE、正金额、非红冲的 PERF_EXPECT。
     */
    private List<PerformanceFact> loadRefundableExpectFacts(String contractNo, String orderNo) {
        List<PerformanceFact> facts = factMapper.selectActiveFactsByContractNo(
            null, FactType.PERF_EXPECT.getCode(), contractNo);
        String orderKey = StringUtils.isBlank(orderNo) ? null : orderNo.trim();
        return facts.stream()
            .filter(f -> f.getReversalType() == null)
            .filter(f -> f.getPerformanceAmount() != null && f.getPerformanceAmount().signum() > 0)
            .filter(f -> orderKey == null || orderKey.equals(f.getOrderNo())
                || orderKey.equals(f.getContractNo()))
            .collect(Collectors.toList());
    }

    /** 可退实收明细：合同（+订单）下 ACTIVE、正金额的 PERF_REAL 明细 */
    private List<PerformanceFactSummaryDTO> loadRefundableRealFacts(String contractNo, String orderNo) {
        ReceivedRealFactPort port = receivedRealFactPortProvider.getIfAvailable();
        if (port == null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        keys.add(contractNo);
        if (StringUtils.isNotBlank(orderNo)) {
            keys.add(orderNo.trim());
        }
        List<PerformanceFactSummaryDTO> reals = port.findActiveByBizKeys(keys);
        String orderKey = StringUtils.isBlank(orderNo) ? null : orderNo.trim();
        return reals.stream()
            .filter(r -> r.getAmount() != null && r.getAmount().signum() > 0)
            .filter(r -> orderKey == null || orderKey.equals(r.getOrderNo())
                || orderKey.equals(r.getContractNo()))
            .collect(Collectors.toList());
    }

    /**
     * 退单拦截原因：仅校验「退单落账月」封账。
     * <p>
     * 原事实归属月封账（已发薪）不再拦截——退单是独立业务单据，负流水只落在退单月，
     * 不动原期间事实，历史已发薪期间不受影响；只要退单月未封账（待结算月）即可退单。
     *
     * @return 拦截原因；可退返回 null
     */
    private String blockReason(String refundPeriod) {
        if (StringUtils.isNotBlank(refundPeriod) && periodCloseService.isClosed(refundPeriod)) {
            return "退单月份 " + refundPeriod + " 已封账（已算薪锁定），不可退单";
        }
        return null;
    }

    /** 退单明细快照（审批详情/溯源用） */
    private List<Map<String, Object>> buildPayload(List<PerformanceFact> facts) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (PerformanceFact f : facts) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("factId", f.getId());
            line.put("period", f.getPeriod());
            line.put("employeeId", f.getEmployeeId());
            line.put("employeeCode", f.getEmployeeExternalCode());
            line.put("roleType", f.getRoleType());
            line.put("roleName", f.getRoleName());
            line.put("shareRatio", f.getShareRatio());
            line.put("amount", MoneyUtil.round2(f.getPerformanceAmount().negate()));
            lines.add(line);
        }
        return lines;
    }

    // ==================== 撤回 / 回调 / 认领 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id, Long userId) {
        PerfRefund refund = requireRefund(id);
        if (refund.getStatus() != AdjustStatus.SUBMITTED) {
            throw new ServiceException("仅审批中的退单可撤回");
        }
        if (!Objects.equals(refund.getApplicantId(), userId)) {
            throw new ServiceException("仅发起人可撤回退单");
        }
        approvalPort.cancel(BizType.PERF_REFUND, id);
        transit(refund, AdjustStatus.CANCELLED, userId, null);
        log.info("[退单] 已撤回：refundId={}, operatorId={}", id, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleWorkflowEvent(Long id, String status, String handler, String message) {
        PerfRefund refund = requireRefund(id);
        if (WF_STATUS_FINISH.equals(status)) {
            if (refund.getStatus() == AdjustStatus.EXECUTED) {
                log.info("[退单] 重复 finish 回调，跳过：refundId={}", id);
                return;
            }
            if (refund.getStatus() != AdjustStatus.SUBMITTED
                && refund.getStatus() != AdjustStatus.APPROVED) {
                log.warn("[退单] 非审批中状态收到 finish，跳过：refundId={}, status={}", id, refund.getStatus());
                return;
            }
            transit(refund, AdjustStatus.APPROVED, parseUserId(handler), message);
            execute(refund);
            transit(refund, AdjustStatus.EXECUTED, parseUserId(handler), null);
            return;
        }
        if (WF_STATUS_BACK.equals(status) || WF_STATUS_INVALID.equals(status)
            || WF_STATUS_TERMINATION.equals(status) || WF_STATUS_CANCEL.equals(status)) {
            if (refund.getStatus().isTerminal()) {
                return;
            }
            transit(refund, AdjustStatus.REJECTED, parseUserId(handler), message);
            log.info("[退单] 审批退回/作废：refundId={}, wfStatus={}", id, status);
        }
    }

    @Override
    public boolean claim(Long id) {
        return approvalPort.claim(BizType.PERF_REFUND, id);
    }

    /**
     * 执行退单：生成负新签事实 + 负实收明细 + 已结佣部分的负结佣明细（归属月=退单月），原事实不动。
     */
    private void execute(PerfRefund refund) {
        // 执行阶段复核封账：审批期间退单月可能已算薪锁定，此时负流水无法进当批工资
        String blocked = blockReason(refund.getRefundPeriod());
        if (blocked != null) {
            throw new ServiceException(blocked);
        }
        List<PerformanceFact> facts = loadRefundableExpectFacts(refund.getContractNo(), refund.getOrderNo());
        BigDecimal expectSum = BigDecimal.ZERO;
        // 原事实 ID → 本次生成的负事实 ID（结佣冲减明细要绑定负事实，避免后续发起结佣重复抵扣）
        Map<Long, Long> factMapping = new HashMap<>();
        for (PerformanceFact original : facts) {
            PerformanceFact redink = new PerformanceFact();
            redink.setFactType(original.getFactType());
            redink.setPeriod(refund.getRefundPeriod());
            redink.setBusinessDate(original.getBusinessDate());
            redink.setBatchId(original.getBatchId());
            redink.setNormalizedRecordId(original.getNormalizedRecordId());
            redink.setSourceKey("MANUAL-RFND-" + refund.getId() + "-" + original.getId());
            redink.setOrderNo(original.getOrderNo());
            redink.setContractNo(original.getContractNo());
            redink.setPropertyAddress(original.getPropertyAddress());
            redink.setFeeItem(original.getFeeItem());
            redink.setBizType(original.getBizType());
            redink.setEmployeeId(original.getEmployeeId());
            redink.setEmployeeExternalCode(original.getEmployeeExternalCode());
            redink.setDeptId(original.getDeptId());
            redink.setRoleType(original.getRoleType());
            redink.setRoleName(original.getRoleName());
            redink.setShareRatio(original.getShareRatio());
            redink.setPerformanceAmount(MoneyUtil.round2(original.getPerformanceAmount().negate()));
            redink.setEffectiveDate(original.getEffectiveDate());
            redink.setFactStatus(FactStatus.ACTIVE);
            redink.setSource(PerformanceSource.MANUAL);
            redink.setReversalType(ReversalType.REDINK_REFUND);
            redink.setRefundOfFactId(original.getId());
            redink.setOperatorId(refund.getOperatorId());
            factMapper.insert(redink);
            factMapping.put(original.getId(), redink.getId());
            expectSum = expectSum.add(redink.getPerformanceAmount());
        }

        ReceivedRealFactPort port = receivedRealFactPortProvider.getIfAvailable();
        BigDecimal realSum = BigDecimal.ZERO;
        int realCount = 0;
        // 本次生成的负实收明细 ID，循环结束后统一挂到退单月实收审批单（实收管理页按审批单维度展示）
        List<Long> refundRealDetailIds = new ArrayList<>();
        if (port != null) {
            List<PerformanceFactSummaryDTO> reals = loadRefundableRealFacts(
                refund.getContractNo(), refund.getOrderNo());
            for (PerformanceFactSummaryDTO real : reals) {
                BigDecimal negative = MoneyUtil.round2(real.getAmount().negate());
                Long newDetailId = port.createRefundDetail(
                    real.getFactId(), refund.getRefundPeriod(), refund.getOperatorId(), refund.getId());
                if (newDetailId != null) {
                    realSum = realSum.add(negative);
                    realCount++;
                    refundRealDetailIds.add(newDetailId);
                }
            }
            // 负实收挂单：未绑定审批单时实收管理页看不到，按退单月直建 APPROVED 单
            port.createRefundApply(refund.getRefundNo(), refund.getRefundPeriod(), refund.getOrderNo(),
                refund.getContractNo(), refundRealDetailIds, refund.getOperatorId());
        }

        // 负结佣：仅对已结佣（APPROVED 结佣明细）部分生成同额负明细，落退单月；
        // 未结佣部分留给后续发起结佣读取负新签事实自然抵扣，避免重复冲减
        CommissionRefundPort commissionPort = commissionRefundPortProvider.getIfAvailable();
        BigDecimal commissionSum = BigDecimal.ZERO;
        if (commissionPort != null) {
            commissionSum = commissionPort.createRefundReversal(
                refund.getContractNo(), refund.getOrderNo(), refund.getRefundPeriod(),
                refund.getId(), refund.getOperatorId(), factMapping);
        }

        refund.setExpectAmount(MoneyUtil.round2(expectSum));
        refund.setRealAmount(MoneyUtil.round2(realSum));
        refund.setCommissionAmount(MoneyUtil.round2(commissionSum));
        refundMapper.updateById(refund);
        log.info("[退单] 执行完成：refundId={}, 负新签={}（{}笔）, 负实收={}（{}笔）, 负结佣={}, 落账月={}",
            refund.getId(), expectSum, facts.size(), realSum, realCount, commissionSum, refund.getRefundPeriod());
    }

    private PerfRefund requireRefund(Long id) {
        PerfRefund refund = refundMapper.selectById(id);
        if (refund == null) {
            throw new ServiceException("退单不存在：{}", id);
        }
        return refund;
    }

    private void transit(PerfRefund refund, AdjustStatus target, Long operatorId, String message) {
        refund.setStatus(target);
        refund.setOperatorId(operatorId);
        if (target == AdjustStatus.APPROVED) {
            refund.setApproverId(operatorId);
            refund.setApproveTime(LocalDateTime.now());
        }
        if (target == AdjustStatus.EXECUTED) {
            refund.setExecuteTime(LocalDateTime.now());
        }
        if (StringUtils.isNotBlank(message) && target == AdjustStatus.REJECTED) {
            refund.setReason(refund.getReason() + "｜审批意见：" + message);
        }
        refundMapper.updateById(refund);
    }

    private Long parseUserId(String handler) {
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
