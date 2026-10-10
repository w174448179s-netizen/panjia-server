package com.panjia.performance.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.dto.CommissionAdjustMirrorDTO;
import com.panjia.contracts.dto.PerformanceContractSummaryDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.dto.ReceivedAlignmentResultDTO;
import com.panjia.contracts.port.CommissionPerformanceQueryPort;
import com.panjia.contracts.port.ReceivedRealFactPort;
import com.panjia.performance.domain.AdjustStatus;
import com.panjia.performance.domain.AdjustType;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceAdjust;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.PerformanceSource;
import com.panjia.performance.domain.ReversedReason;
import com.panjia.performance.domain.bo.AddMemberPayload;
import com.panjia.performance.mapper.PerformanceAdjustMapper;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.service.ReceivedAlignmentService;
import com.panjia.performance.service.ReverseService;
import com.panjia.performance.util.MoneyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业绩事实跨域查询适配器（panjia-performance 实现 contracts {@link CommissionPerformanceQueryPort}）。
 * <p>
 * 设计说明：CommissionPerformanceQueryPort 是跨域端口（定义在 panjia-contracts），
 * 实现方在数据归属域（panjia-performance）。依赖方向 commission → contracts ← performance，
 * 结佣域完全不知道业绩域的 mapper / entity，严禁直连 {@code pj_perf_*} 表（CI C3/C4）。
 * <p>
 * 口径：findActive* 只返回 ACTIVE；金额取 performance_amount 原样值；
 * getByFactId 不限状态（溯源需能看到已冲销事实）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CommissionPerformanceAdapter implements CommissionPerformanceQueryPort {

    private final PerformanceFactMapper factMapper;
    private final ReceivedAlignmentService receivedAlignmentService;
    private final ReverseService reverseService;
    private final PerformanceAdjustMapper adjustMapper;
    private final com.panjia.contracts.port.EmployeeMainDataQueryPort employeeMainDataQueryPort;
    /**
     * 实收域端口（PERF_REAL 拆表后唯一读写落地处）。ObjectProvider 惰性取用：
     * 实现 bean 在 panjia-received（performance 不反向依赖 received），运行期由 Spring 装配，
     * 端口实现缺失时回退空结果/原 PERF_EXPECT 路径，保证本模块上下文可独立启动。
     */
    private final ObjectProvider<ReceivedRealFactPort> receivedRealFactPortProvider;

    /** PERF_REAL 读/写全部委托实收域端口；端口实现缺失时返回 null（调用方按空结果兜底）。 */
    private ReceivedRealFactPort realPort() {
        return receivedRealFactPortProvider.getIfAvailable();
    }

    private static boolean isReal(String factType) {
        return FactType.PERF_REAL.getCode().equals(factType);
    }

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByDept(String period, Long deptId, String factType) {
        if (isReal(factType)) {
            ReceivedRealFactPort port = realPort();
            return port == null ? Collections.emptyList() : port.findActiveByDept(period, deptId);
        }
        LambdaQueryWrapper<PerformanceFact> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PerformanceFact::getPeriod, period)
            .eq(deptId != null, PerformanceFact::getDeptId, deptId)
            .eq(FactType.fromCode(factType) != null, PerformanceFact::getFactType, FactType.fromCode(factType))
            .eq(PerformanceFact::getFactStatus, FactStatus.ACTIVE)
            .orderByAsc(PerformanceFact::getId);
        return toSummaries(factMapper.selectList(wrapper));
    }

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByEmployee(String period, Long employeeId, String factType) {
        if (isReal(factType)) {
            ReceivedRealFactPort port = realPort();
            return port == null ? Collections.emptyList() : port.findActiveByEmployee(period, employeeId);
        }
        LambdaQueryWrapper<PerformanceFact> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PerformanceFact::getPeriod, period)
            .eq(PerformanceFact::getEmployeeId, employeeId)
            .eq(FactType.fromCode(factType) != null, PerformanceFact::getFactType, FactType.fromCode(factType))
            .eq(PerformanceFact::getFactStatus, FactStatus.ACTIVE)
            .orderByAsc(PerformanceFact::getId);
        return toSummaries(factMapper.selectList(wrapper));
    }

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByFacts(java.util.Collection<Long> factIds) {
        if (factIds == null || factIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<PerformanceFactSummaryDTO> all = factMapper.selectFactSummariesByIds(factIds);
        // 拆表后 PERF_REAL 明细在实收域：pj_perf_fact 查不到的 ID 再去 rd 补查（含历史单绑 rd.id 的场景）
        Set<Long> foundIds = new HashSet<>();
        for (PerformanceFactSummaryDTO dto : all) {
            foundIds.add(dto.getFactId());
        }
        List<Long> missingIds = new ArrayList<>();
        for (Long id : factIds) {
            if (id != null && !foundIds.contains(id)) {
                missingIds.add(id);
            }
        }
        ReceivedRealFactPort port = realPort();
        if (port != null && !missingIds.isEmpty()) {
            all = new ArrayList<>(all);
            all.addAll(port.findActiveByIds(missingIds));
        }
        List<PerformanceFactSummaryDTO> active = new ArrayList<>(all.size());
        for (PerformanceFactSummaryDTO dto : all) {
            if ("ACTIVE".equals(dto.getFactStatus())) {
                active.add(dto);
            }
        }
        enrichWithEmployeeData(active);
        return active;
    }

    @Override
    public PerformanceFactSummaryDTO getByFactId(Long factId) {
        if (factId == null) {
            return null;
        }
        List<PerformanceFactSummaryDTO> list = factMapper.selectFactSummariesByIds(Collections.singletonList(factId));
        if (!list.isEmpty()) {
            enrichWithEmployeeData(list);
            return list.get(0);
        }
        // pj_perf_fact 查不到：尝试实收明细 ID（rd.id，不限状态，溯源用）
        ReceivedRealFactPort port = realPort();
        return port == null ? null : port.getById(factId);
    }

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByContract(String period, String contractNo, String factType) {
        if (isReal(factType)) {
            ReceivedRealFactPort port = realPort();
            return port == null ? Collections.emptyList() : port.findActiveByContract(period, contractNo);
        }
        List<PerformanceFactSummaryDTO> list = factMapper.selectActiveFactSummariesByContractNo(period, factType, contractNo);
        enrichWithEmployeeData(list);
        return list;
    }

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByBizKeys(java.util.Collection<String> bizKeys, String factType) {
        if (bizKeys == null || bizKeys.isEmpty()) {
            return Collections.emptyList();
        }
        if (isReal(factType)) {
            ReceivedRealFactPort port = realPort();
            return port == null ? Collections.emptyList() : port.findActiveByBizKeys(bizKeys);
        }
        // 跨月查找：order_no 或 contract_no 命中键集合即返回（新签可能早于到账月）
        LambdaQueryWrapper<PerformanceFact> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FactType.fromCode(factType) != null, PerformanceFact::getFactType, FactType.fromCode(factType))
            .eq(PerformanceFact::getFactStatus, FactStatus.ACTIVE)
            .and(w -> w.in(PerformanceFact::getOrderNo, bizKeys)
                .or().in(PerformanceFact::getContractNo, bizKeys))
            .orderByAsc(PerformanceFact::getId);
        List<PerformanceFactSummaryDTO> list = toSummaries(factMapper.selectList(wrapper));
        enrichWithEmployeeData(list);
        return list;
    }

    @Override
    public Map<String, BigDecimal> sumExpectAmountsByKeysCrossPeriod(java.util.Collection<String> bizKeys) {
        if (bizKeys == null || bizKeys.isEmpty()) {
            return Collections.emptyMap();
        }
        LambdaQueryWrapper<PerformanceFact> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PerformanceFact::getFactType, FactType.PERF_EXPECT)
            .eq(PerformanceFact::getFactStatus, FactStatus.ACTIVE)
            .and(w -> w.in(PerformanceFact::getOrderNo, bizKeys)
                .or().in(PerformanceFact::getContractNo, bizKeys))
            .select(PerformanceFact::getOrderNo, PerformanceFact::getContractNo,
                PerformanceFact::getPerformanceAmount);
        java.util.Set<String> wanted = new java.util.HashSet<>(bizKeys);
        Map<String, BigDecimal> result = new HashMap<>();
        for (PerformanceFact f : factMapper.selectList(wrapper)) {
            BigDecimal amt = f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount();
            // 同一事实同时计入订单号键与合同号键（调用方按输入键取值）
            if (StringUtils.isNotBlank(f.getOrderNo()) && wanted.contains(f.getOrderNo())) {
                result.merge(f.getOrderNo(), amt, BigDecimal::add);
            }
            if (StringUtils.isNotBlank(f.getContractNo()) && wanted.contains(f.getContractNo())) {
                result.merge(f.getContractNo(), amt, BigDecimal::add);
            }
        }
        return result;
    }

    @Override
    public List<PerformanceContractSummaryDTO> listContractSummaries(String period, Long deptId, String factType, Long employeeId, String keyword) {
        if (isReal(factType)) {
            ReceivedRealFactPort port = realPort();
            return port == null ? Collections.emptyList()
                : port.listContractSummaries(period, deptId, employeeId, keyword);
        }
        return factMapper.selectContractSummaries(period, factType, deptId, employeeId, keyword);
    }

    @Override
    public long countDistinctEmployeesByKeys(String period, java.util.Collection<String> bizKeys, String factType) {
        if (bizKeys == null || bizKeys.isEmpty()) {
            return 0L;
        }
        if (isReal(factType)) {
            ReceivedRealFactPort port = realPort();
            return port == null ? 0L : port.countDistinctEmployeesByKeys(period, bizKeys);
        }
        return factMapper.selectDistinctEmployeeCountByKeys(period, factType, bizKeys);
    }

    @Override
    public Map<String, Set<String>> listActivePeriodsByKeys(Collection<String> bizKeys, String factType) {
        if (bizKeys == null || bizKeys.isEmpty()) {
            return Collections.emptyMap();
        }
        if (isReal(factType)) {
            ReceivedRealFactPort port = realPort();
            return port == null ? Collections.emptyMap() : port.listActivePeriodsByKeys(bizKeys);
        }
        return groupPeriodsByKeys(factMapper.selectActivePeriodsByKeys(factType, bizKeys));
    }

    /** 行集 → bizKey → 期间集合（行列别名 bizKey/period，大小写敏感）。 */
    private static Map<String, Set<String>> groupPeriodsByKeys(List<Map<String, Object>> rows) {
        Map<String, Set<String>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get("bizKey");
            Object period = row.get("period");
            if (key != null && period != null) {
                result.computeIfAbsent(String.valueOf(key), k -> new HashSet<>()).add(String.valueOf(period));
            }
        }
        return result;
    }

    @Override
    public Map<String, BigDecimal> sumOriginalAmountsByKeys(String period, java.util.Collection<String> bizKeys, String factType) {
        if (bizKeys == null || bizKeys.isEmpty()) {
            return Collections.emptyMap();
        }
        if (isReal(factType)) {
            ReceivedRealFactPort port = realPort();
            return port == null ? Collections.emptyMap() : port.sumOriginalAmountsByKeys(period, bizKeys);
        }
        List<Map<String, Object>> rows = factMapper.selectOriginalFactAmountsByKeys(period, factType, bizKeys);
        Map<String, BigDecimal> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get("bizKey");
            Object amount = row.get("originalAmount");
            if (key != null && amount instanceof BigDecimal bd) {
                result.put(String.valueOf(key), bd);
            }
        }
        return result;
    }

    @Override
    public ReceivedAlignmentResultDTO alignReceivedToExpected(String period, String contractNo, Long operatorId) {
        return receivedAlignmentService.align(period, contractNo, operatorId);
    }

    // ==================== 历史工资导入（HISTORY_PAYROLL）结佣建单支撑 ====================

    @Override
    public List<com.panjia.contracts.dto.HistoryRealFactDTO> listRealFactsByBatch(String period, Long batchId) {
        if (period == null || period.isBlank() || batchId == null) {
            return Collections.emptyList();
        }
        // 拆表后 PERF_REAL 历史工资明细在实收域 rd（source_batch_id），统一委托实收端口
        ReceivedRealFactPort port = realPort();
        return port == null ? Collections.emptyList() : port.listRealFactsByBatch(period, batchId);
    }

    @Override
    public Map<String, BigDecimal> sumExpectAmountsByKeys(String period, java.util.Collection<String> bizKeys) {
        if (period == null || period.isBlank() || bizKeys == null || bizKeys.isEmpty()) {
            return Collections.emptyMap();
        }
        LambdaQueryWrapper<PerformanceFact> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PerformanceFact::getPeriod, period)
            .eq(PerformanceFact::getFactType, FactType.PERF_EXPECT)
            .eq(PerformanceFact::getFactStatus, FactStatus.ACTIVE);
        List<PerformanceFact> facts = factMapper.selectList(wrapper);
        java.util.Set<String> wanted = new java.util.HashSet<>(bizKeys);
        Map<String, BigDecimal> result = new HashMap<>();
        for (PerformanceFact f : facts) {
            String key = bizKeyOf(f);
            if (key == null || !wanted.contains(key)) {
                continue;
            }
            result.merge(key, f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount(),
                BigDecimal::add);
        }
        return result;
    }

    /** 业务键：订单号优先，空回退合同号，再回退 sourceKey（同老导入器 bizKeyOf） */
    private String bizKeyOf(PerformanceFact f) {
        if (StringUtils.isNotBlank(f.getOrderNo())) {
            return f.getOrderNo();
        }
        if (StringUtils.isNotBlank(f.getContractNo())) {
            return f.getContractNo();
        }
        return f.getSourceKey();
    }

    private List<PerformanceFactSummaryDTO> toSummaries(List<PerformanceFact> facts) {
        if (facts.isEmpty()) return Collections.emptyList();
        List<PerformanceFactSummaryDTO> list = new ArrayList<>(facts.size());
        for (PerformanceFact fact : facts) {
            list.add(toSummary(fact));
        }
        enrichWithEmployeeData(list);
        return list;
    }

    /**
     * 批量填充 employeeName/deptName（员工主数据批量查询，避免 N+1）。
     * <p>
     * employeeCode 由事实表 employee_external_code 直接映射（见 toSummary），
     * 姓名/部门名不在事实表中，统一经 {@link EmployeeMainDataQueryPort} 批量补充。
     * 供 findActiveByFacts / getByFactId / findActiveByContract / toSummaries 共用，
     * 保证导出与列表 tab 同口径。
     */
    private void enrichWithEmployeeData(List<PerformanceFactSummaryDTO> list) {
        if (list == null || list.isEmpty()) return;
        Set<Long> empIds = new HashSet<>();
        for (PerformanceFactSummaryDTO dto : list) {
            if (dto.getEmployeeId() != null) empIds.add(dto.getEmployeeId());
        }
        if (empIds.isEmpty()) return;
        Map<Long, com.panjia.contracts.dto.EmployeeMainDataDTO> empMap =
            employeeMainDataQueryPort.listByIds(empIds);
        for (PerformanceFactSummaryDTO dto : list) {
            com.panjia.contracts.dto.EmployeeMainDataDTO emp =
                dto.getEmployeeId() == null ? null : empMap.get(dto.getEmployeeId());
            if (emp != null) {
                dto.setEmployeeName(emp.getEmployeeName());
                dto.setDeptName(emp.getDeptName());
            }
        }
    }

    private PerformanceFactSummaryDTO toSummary(PerformanceFact fact) {
        PerformanceFactSummaryDTO dto = new PerformanceFactSummaryDTO();
        dto.setFactId(fact.getId());
        dto.setFactType(fact.getFactType() != null ? fact.getFactType().getCode() : null);
        dto.setFactStatus(fact.getFactStatus() != null ? fact.getFactStatus().getCode() : null);
        dto.setPeriod(fact.getPeriod());
        dto.setBusinessDate(fact.getBusinessDate());
        dto.setEmployeeId(fact.getEmployeeId());
        dto.setEmployeeCode(fact.getEmployeeExternalCode());
        dto.setDeptId(fact.getDeptId());
        dto.setBizType(fact.getBizType());
        dto.setRoleType(fact.getRoleType());
        dto.setRoleName(fact.getRoleName());
        dto.setSource(fact.getSource() != null ? fact.getSource().getCode() : null);
        dto.setAmount(fact.getPerformanceAmount());
        dto.setBatchId(fact.getBatchId());
        dto.setNormalizedRecordId(fact.getNormalizedRecordId());
        dto.setSourceKey(fact.getSourceKey());
        dto.setReceivedApplyId(fact.getReceivedApplyId());
        dto.setShareRatio(fact.getShareRatio());
        dto.setContractNo(fact.getContractNo());
        dto.setOrderNo(fact.getOrderNo());
        dto.setPropertyAddress(fact.getPropertyAddress());
        return dto;
    }

    // ==================== 结佣调整：事实变更（同 PerformanceAdjustServiceImpl 口径） ====================

    @Override
    public Map<Long, Long> adjustContractFactsAmount(String period, String contractNo, String factType,
                                                      BigDecimal targetAmount, Long operatorId, Long adjustId) {
        if (isReal(factType)) {
            // PERF_REAL 合同级调整落实收域 rd（分摊 + supersede 由实收端口实现）
            ReceivedRealFactPort port = realPort();
            return port == null ? new HashMap<>()
                : port.adjustContractDetailsAmount(period, contractNo, targetAmount, operatorId, adjustId);
        }
        List<PerformanceFact> facts = factMapper.selectActiveFactsByContractNo(period, factType, contractNo);
        Map<Long, Long> mapping = new HashMap<>();
        if (facts == null || facts.isEmpty()) {
            return mapping;
        }
        BigDecimal total = facts.stream()
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal deltaTotal = MoneyUtil.round2(targetAmount.subtract(total));
        if (MoneyUtil.isZero(deltaTotal)) {
            return mapping;
        }
        List<BigDecimal> amounts = facts.stream()
            .map(f -> f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount())
            .toList();
        BigDecimal[] parts = allocateByAmount(amounts, deltaTotal);
        for (int i = 0; i < facts.size(); i++) {
            if (MoneyUtil.isZero(parts[i])) {
                continue;
            }
            PerformanceFact oldFact = facts.get(i);
            PerformanceFact newFact = copyFactBase(oldFact);
            newFact.setPerformanceAmount(MoneyUtil.round2(oldFact.getPerformanceAmount().add(parts[i])));
            newFact.setAdjustId(adjustId);
            PerformanceFact created = reverseService.supersede(oldFact.getId(), newFact, operatorId);
            mapping.put(oldFact.getId(), created.getId());
        }
        return mapping;
    }

    @Override
    public Long adjustFactAmount(Long factId, BigDecimal targetAmount, Long operatorId, Long adjustId) {
        // 拆表后 factId 可能是 rd.id：实收明细命中则走实收 supersede，否则走业绩事实原路径
        ReceivedRealFactPort port = realPort();
        if (port != null && port.getById(factId) != null) {
            return port.adjustDetailAmount(factId, targetAmount, operatorId, adjustId);
        }
        PerformanceFact oldFact = resolveActiveFactOrSuccessor(factId);
        if (oldFact == null) {
            return null;
        }
        PerformanceFact newFact = copyFactBase(oldFact);
        newFact.setPerformanceAmount(MoneyUtil.round2(targetAmount));
        newFact.setAdjustId(adjustId);
        PerformanceFact created = reverseService.supersede(oldFact.getId(), newFact, operatorId);
        return created.getId();
    }

    @Override
    public Long adjustFactAmount(Long factId, BigDecimal targetAmount, BigDecimal shareRatio,
                                 Long operatorId, Long adjustId) {
        PerformanceFact oldFact = resolveActiveFactOrSuccessor(factId);
        if (oldFact == null) {
            return null;
        }
        PerformanceFact newFact = copyFactBase(oldFact);
        newFact.setPerformanceAmount(MoneyUtil.round2(targetAmount));
        if (shareRatio != null) {
            newFact.setShareRatio(shareRatio);
        }
        newFact.setAdjustId(adjustId);
        PerformanceFact created = reverseService.supersede(oldFact.getId(), newFact, operatorId);
        return created.getId();
    }

    /**
     * 解析待冲销事实：若 factId 仍 ACTIVE 则直接返回；
     * 若已被其他操作冲销（REVERSED），按身份键（员工+角色+业务类型+费项+业务日期+期间+合同）
     * 定位唯一 ACTIVE 后继行自动续接。
     * <p>
     * 典型场景：结佣调整审批期间，同一合同另一张调整单抢先执行、把明细绑定的事实冲销并换了 ID；
     * 本张调整单执行时若直接 supersede 旧 factId 会触发「事实状态不可冲销」。此处自动续接到
     * 当前 ACTIVE 后继行，避免并发调整互相卡死。
     * <p>
     * 无法唯一续接（0 条或多条候选）时抛业务异常，提示撤回重发。
     */
    private PerformanceFact resolveActiveFactOrSuccessor(Long factId) {
        PerformanceFact fact = factMapper.selectById(factId);
        if (fact == null) {
            return null;
        }
        if (fact.getFactStatus() == FactStatus.ACTIVE) {
            return fact;
        }
        // 已冲销：按身份键找唯一 ACTIVE 后继（supersede 时这些键原样继承，见 copyFactBase）
        List<PerformanceFact> successors = factMapper.selectList(new LambdaQueryWrapper<PerformanceFact>()
            .eq(PerformanceFact::getFactStatus, FactStatus.ACTIVE)
            .eq(PerformanceFact::getFactType, fact.getFactType())
            .eq(PerformanceFact::getPeriod, fact.getPeriod())
            .eq(PerformanceFact::getContractNo, fact.getContractNo())
            .eq(PerformanceFact::getEmployeeId, fact.getEmployeeId())
            .eq(PerformanceFact::getRoleType, fact.getRoleType())
            .eq(PerformanceFact::getBizType, fact.getBizType())
            .eq(PerformanceFact::getFeeItem, fact.getFeeItem())
            .eq(PerformanceFact::getBusinessDate, fact.getBusinessDate()));
        if (successors.size() == 1) {
            PerformanceFact successor = successors.get(0);
            log.warn("[结佣适配] 事实已被冲销，自动续接到后继 ACTIVE 行：oldFactId={}, newFactId={}, oldStatus={}",
                factId, successor.getId(), fact.getFactStatus());
            return successor;
        }
        throw new ServiceException("执行失败：结佣明细绑定的业绩事实已被其他调整替换且无法唯一匹配当前明细"
            + "（候选 " + successors.size() + " 条），请撤回该调整单后按最新明细重新发起：factId=" + factId);
    }

    @Override
    public Long createMemberFact(Long templateFactId, Long employeeId, String employeeCode, Long deptId,
                                 String roleType, BigDecimal amount, BigDecimal shareRatio,
                                 Long operatorId, Long adjustId) {
        PerformanceFact template = factMapper.selectById(templateFactId);
        if (template == null) {
            return null;
        }
        PerformanceFact newFact = copyFactBase(template);
        newFact.setEmployeeId(employeeId);
        newFact.setEmployeeExternalCode(employeeCode);
        if (deptId != null) {
            newFact.setDeptId(deptId);
        }
        newFact.setRoleType(roleType);
        newFact.setRoleName(roleType);
        // 新角色人占比：显式指定则落库，未指定保持 null（不继承模板行占比）
        newFact.setShareRatio(shareRatio);
        newFact.setPerformanceAmount(MoneyUtil.round2(amount));
        newFact.setAdjustId(adjustId);
        newFact.setBatchId(null);
        newFact.setNormalizedRecordId(null);
        newFact.setSource(PerformanceSource.MANUAL);
        // sourceKey 对齐导入格式，角色人系统号用 MANUAL-CADJ-{adjustId} 虚拟值避开导入幂等键
        newFact.setSourceKey(StringUtils.defaultString(template.getOrderNo()) + "|"
            + StringUtils.defaultString(template.getContractNo()) + "|MANUAL-CADJ-" + adjustId
            + "|" + StringUtils.defaultString(template.getFeeItem()) + "|"
            + StringUtils.defaultString(roleType));
        factMapper.insert(newFact);
        return newFact.getId();
    }

    @Override
    public void recordExecutedAdjustMirror(CommissionAdjustMirrorDTO mirror) {
        PerformanceAdjust adjust = new PerformanceAdjust();
        adjust.setAdjustNo(mirror.getAdjustNo());
        adjust.setFactId(mirror.getFactId());
        adjust.setPeriod(mirror.getPeriod());
        adjust.setAdjustType(AdjustType.valueOf(mirror.getAdjustType()));
        adjust.setAdjustScope(mirror.getAdjustScope());
        adjust.setContractNo(mirror.getContractNo());
        adjust.setFactType(FactType.PERF_EXPECT.name());
        adjust.setOriginalAmount(mirror.getOriginalAmount());
        // 合同级调整无单员工/单部门，从合同首条 ACTIVE 事实回填（两列 NOT NULL），与新签合同级调整一致；
        // 明细级调整从指定 factId 回填员工/部门
        if ("CONTRACT".equals(mirror.getAdjustScope())) {
            List<PerformanceFactSummaryDTO> facts = findActiveByContract(
                mirror.getPeriod(), mirror.getContractNo(), FactType.PERF_EXPECT.name());
            if (!facts.isEmpty()) {
                adjust.setDeptId(facts.get(0).getDeptId());
            }
            adjust.setEmployeeId(0L);
        } else if (mirror.getFactId() != null) {
            PerformanceFactSummaryDTO fact = getByFactId(mirror.getFactId());
            if (fact != null) {
                adjust.setEmployeeId(fact.getEmployeeId());
                adjust.setDeptId(fact.getDeptId());
            } else {
                adjust.setEmployeeId(0L);
            }
        } else {
            adjust.setEmployeeId(0L);
        }
        adjust.setTargetAmount(mirror.getTargetAmount());
        adjust.setReason(mirror.getReason());
        adjust.setStatus(AdjustStatus.EXECUTED);
        adjust.setApplicantId(mirror.getApplicantId());
        adjust.setApproverId(mirror.getApproverId());
        adjust.setOperatorId(mirror.getApproverId());
        LocalDateTime now = LocalDateTime.now();
        adjust.setApproveTime(now);
        adjust.setExecuteTime(now);
        if (AdjustType.ADD_MEMBER.name().equals(mirror.getAdjustType())) {
            adjust.setEmployeeId(mirror.getNewEmployeeId());
            adjust.setPayloadJson(buildAddMemberPayloadJson(mirror));
        }
        adjustMapper.insert(adjust);
    }

    /**
     * 组装新签侧 ADD_MEMBER 快照 JSON（AddMemberPayload 结构）：
     * allocations 携带旧事实 ID/员工/调整前后差额，供新签明细页逆向还原每人调整前金额。
     */
    private String buildAddMemberPayloadJson(CommissionAdjustMirrorDTO mirror) {
        AddMemberPayload payload = new AddMemberPayload();
        payload.setNewEmployeeId(mirror.getNewEmployeeId());
        payload.setEmployeeCode(mirror.getNewEmployeeCode());
        payload.setEmployeeName(mirror.getNewEmployeeName());
        payload.setDeptId(mirror.getNewDeptId());
        payload.setRoleType(mirror.getNewRoleType());
        payload.setAmount(mirror.getTargetAmount());
        payload.setNewShareRatio(mirror.getNewShareRatio());
        payload.setContractTotal(mirror.getOriginalAmount());
        payload.setAfterTotal(mirror.getAfterTotal());
        if (mirror.getAllocations() != null) {
            List<AddMemberPayload.Alloc> allocs = new ArrayList<>();
            for (CommissionAdjustMirrorDTO.Alloc a : mirror.getAllocations()) {
                AddMemberPayload.Alloc alloc = new AddMemberPayload.Alloc();
                alloc.setFactId(a.getFactId());
                alloc.setEmployeeId(a.getEmployeeId());
                alloc.setEmployeeName(a.getEmployeeName());
                alloc.setBefore(a.getBefore());
                alloc.setDelta(a.getDelta());
                allocs.add(alloc);
            }
            payload.setAllocations(allocs);
        }
        return JsonUtils.toJsonString(payload);
    }

    @Override
    public void voidFact(Long factId, Long operatorId, Long adjustId) {
        // rd 命中：实收明细单行冲销（不插新行）；否则走业绩事实冲销
        ReceivedRealFactPort port = realPort();
        if (port != null && port.getById(factId) != null) {
            port.voidDetail(factId, operatorId, adjustId);
            return;
        }
        reverseService.reverseByAdjust(factId, adjustId, ReversedReason.MANUAL_ADJUST, operatorId);
    }

    @Override
    public Long transferFact(Long factId, Long targetDeptId, Long operatorId, Long adjustId) {
        // rd 命中：实收明细部门划转 supersede；否则走业绩事实原路径
        ReceivedRealFactPort port = realPort();
        if (port != null && port.getById(factId) != null) {
            return port.transferDetail(factId, targetDeptId, operatorId, adjustId);
        }
        PerformanceFact oldFact = factMapper.selectById(factId);
        if (oldFact == null) {
            return null;
        }
        PerformanceFact newFact = copyFactBase(oldFact);
        newFact.setDeptId(targetDeptId);
        newFact.setAdjustId(adjustId);
        PerformanceFact created = reverseService.supersede(oldFact.getId(), newFact, operatorId);
        return created.getId();
    }

    /** 按金额占比分摊差额（与 PerformanceAdjustServiceImpl.allocateByAmount 同口径）。 */
    private BigDecimal[] allocateByAmount(List<BigDecimal> amounts, BigDecimal deltaTotal) {
        BigDecimal total = amounts.stream()
            .map(a -> a == null ? BigDecimal.ZERO : a)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal[] parts = new BigDecimal[amounts.size()];
        if (total.signum() == 0) {
            BigDecimal even = amounts.isEmpty() ? BigDecimal.ZERO
                : MoneyUtil.round2(deltaTotal.divide(BigDecimal.valueOf(amounts.size()), 8, RoundingMode.HALF_UP));
            BigDecimal allocated = BigDecimal.ZERO;
            for (int i = 0; i < amounts.size(); i++) {
                parts[i] = even;
                allocated = allocated.add(even);
            }
            if (!amounts.isEmpty()) {
                parts[0] = MoneyUtil.round2(parts[0].add(deltaTotal.subtract(allocated)));
            }
            return parts;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        int largestIdx = 0;
        BigDecimal largestAbs = BigDecimal.ZERO;
        for (int i = 0; i < amounts.size(); i++) {
            BigDecimal abs = amounts.get(i).abs();
            if (abs.compareTo(largestAbs) > 0) {
                largestAbs = abs;
                largestIdx = i;
            }
            parts[i] = MoneyUtil.round2(deltaTotal.multiply(amounts.get(i)).divide(total, 8, RoundingMode.HALF_UP));
            allocated = allocated.add(parts[i]);
        }
        BigDecimal tail = MoneyUtil.round2(deltaTotal.subtract(allocated));
        parts[largestIdx] = MoneyUtil.round2(parts[largestIdx].add(tail));
        return parts;
    }

    /** 复制事实基础字段（batchId 置空，避免被批次 supersede 误冲销），与新签调整一致。 */
    private PerformanceFact copyFactBase(PerformanceFact oldFact) {
        PerformanceFact newFact = new PerformanceFact();
        newFact.setFactType(oldFact.getFactType());
        newFact.setPeriod(oldFact.getPeriod());
        newFact.setBusinessDate(oldFact.getBusinessDate());
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
}
