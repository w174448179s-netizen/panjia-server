package com.panjia.performance.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.dto.PerformanceContractSummaryDTO;
import com.panjia.contracts.dto.PerformanceFactSummaryDTO;
import com.panjia.contracts.port.CommissionPerformanceQueryPort;
import com.panjia.contracts.port.ReceivedRealFactPort;
import com.panjia.performance.domain.AdjustStatus;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceAdjust;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.mapper.PerformanceAdjustMapper;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.service.BizKeyMatchGuard;
import com.panjia.performance.service.ReverseService;
import com.panjia.performance.util.MoneyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
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
    /** 双键未命中退化合同号匹配的单侧闸门（事实行订单唯一性） */
    private final BizKeyMatchGuard bizKeyMatchGuard;
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

    /**
     * 结佣侧新签事实装载/执行的<b>双侧</b>退化闸门：新签 ACTIVE 事实订单维度唯一
     * <b>且</b>实收合同表全局订单维度唯一才允许双键未命中后退化按合同号匹配。
     * <p>比业绩域单侧闸门更严：结佣金额归属到实收订单驱动的结佣单，须防止同笔脏新签
     * （order_no 误填成合同号）被多个实收订单（含分批到账）重复归属。
     */
    private boolean canDegradeExpectToReceived(String period, String contractNo) {
        if (!bizKeyMatchGuard.canDegradeActiveExpect(period, contractNo)) {
            return false;
        }
        ReceivedRealFactPort port = realPort();
        return port != null && port.countDistinctReceivedOrders(contractNo) == 1L;
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
    public Map<String, BigDecimal> sumActiveByEmployeePeriods(java.util.Collection<Long> employeeIds, String fromPeriod, String toPeriod) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, BigDecimal> result = new HashMap<>();
        for (Map<String, Object> row : factMapper.sumActiveByEmployeePeriods(employeeIds, fromPeriod, toPeriod)) {
            Object empId = row.get("employeeId");
            Object period = row.get("period");
            Object total = row.get("totalAmount");
            if (empId == null || period == null || total == null) {
                continue;
            }
            result.put(((Number) empId).longValue() + "|" + period, (BigDecimal) total);
        }
        return result;
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
            return port == null ? Collections.emptyList() : port.findActiveByContract(period, null, contractNo);
        }
        List<PerformanceFactSummaryDTO> list = factMapper.selectActiveFactSummariesByContractNo(period, factType, contractNo);
        enrichWithEmployeeData(list);
        return list;
    }

    @Override
    public List<PerformanceFactSummaryDTO> findActiveByBizKey(String period, String orderNo, String contractNo, String factType) {
        if (isReal(factType)) {
            // 实收口径同走订单号+合同号双键精确匹配（orderNo 为空退化合同号/订单号双键 OR）
            ReceivedRealFactPort port = realPort();
            return port == null ? Collections.emptyList() : port.findActiveByContract(period, orderNo, contractNo);
        }
        if (StringUtils.isNotBlank(orderNo)) {
            List<PerformanceFactSummaryDTO> list = factMapper.selectActiveFactSummariesByOrderAndContract(period, factType, orderNo, contractNo);
            if (list != null && !list.isEmpty()) {
                enrichWithEmployeeData(list);
                return list;
            }
            // 双键未命中：双侧闸门（新签事实+实收合同均订单唯一）才退化，兼容 order_no 误填合同号
            if (canDegradeExpectToReceived(period, contractNo)) {
                log.warn("[业绩端口] 双键未命中且新签/实收两侧订单唯一，退化合同号匹配：orderNo={}, contractNo={}, period={}, factType={}",
                    orderNo, contractNo, period, factType);
                list = factMapper.selectActiveFactSummariesByContractNo(period, factType, contractNo);
                enrichWithEmployeeData(list);
                return list;
            }
            log.warn("[业绩端口] 双键未命中且两侧不满足订单唯一，禁止退化合同号匹配：orderNo={}, contractNo={}, period={}, factType={}",
                orderNo, contractNo, period, factType);
            return Collections.emptyList();
        }
        return findActiveByContract(period, contractNo, factType);
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
    public Map<Long, String> findNewSignPeriodsByRealFacts(java.util.Collection<PerformanceFactSummaryDTO> realFacts) {
        if (realFacts == null || realFacts.isEmpty()) {
            return Collections.emptyMap();
        }
        // 收集业务键（订单号/合同号），跨期间查 ACTIVE 新签事实（新签可能早于实收多个归属月）
        Set<String> bizKeys = new HashSet<>();
        for (PerformanceFactSummaryDTO f : realFacts) {
            if (StringUtils.isNotBlank(f.getOrderNo())) {
                bizKeys.add(f.getOrderNo());
            }
            if (StringUtils.isNotBlank(f.getContractNo())) {
                bizKeys.add(f.getContractNo());
            }
        }
        if (bizKeys.isEmpty()) {
            return Collections.emptyMap();
        }
        LambdaQueryWrapper<PerformanceFact> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PerformanceFact::getFactType, FactType.PERF_EXPECT)
            .eq(PerformanceFact::getFactStatus, FactStatus.ACTIVE)
            .and(w -> w.in(PerformanceFact::getOrderNo, bizKeys)
                .or().in(PerformanceFact::getContractNo, bizKeys))
            .select(PerformanceFact::getOrderNo, PerformanceFact::getContractNo,
                PerformanceFact::getEmployeeId, PerformanceFact::getPeriod);
        // (orderNo|contractNo, employeeId) → 最早归属月（新签月以最初导入月为准，跨月补差取 MIN）
        Map<String, String> minPeriodByKeyEmp = new HashMap<>();
        for (PerformanceFact f : factMapper.selectList(wrapper)) {
            if (f.getEmployeeId() == null || StringUtils.isBlank(f.getPeriod())) {
                continue;
            }
            if (StringUtils.isNotBlank(f.getOrderNo())) {
                minPeriodByKeyEmp.merge("O|" + f.getOrderNo() + "|" + f.getEmployeeId(), f.getPeriod(),
                    (a, b) -> a.compareTo(b) <= 0 ? a : b);
            }
            if (StringUtils.isNotBlank(f.getContractNo())) {
                minPeriodByKeyEmp.merge("C|" + f.getContractNo() + "|" + f.getEmployeeId(), f.getPeriod(),
                    (a, b) -> a.compareTo(b) <= 0 ? a : b);
            }
        }
        Map<Long, String> result = new HashMap<>();
        for (PerformanceFactSummaryDTO r : realFacts) {
            if (r.getFactId() == null || r.getEmployeeId() == null) {
                continue;
            }
            // 订单号优先精确匹配（同合同多订单防串），空/未命中退化合同号
            String hit = StringUtils.isNotBlank(r.getOrderNo())
                ? minPeriodByKeyEmp.get("O|" + r.getOrderNo() + "|" + r.getEmployeeId()) : null;
            if (hit == null && StringUtils.isNotBlank(r.getContractNo())) {
                hit = minPeriodByKeyEmp.get("C|" + r.getContractNo() + "|" + r.getEmployeeId());
            }
            if (hit != null) {
                result.put(r.getFactId(), hit);
            }
        }
        return result;
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
    public Map<String, BigDecimal> sumExpectAmountsByKeysCrossPeriodForEmployee(
            java.util.Collection<String> bizKeys, Long employeeId) {
        if (bizKeys == null || bizKeys.isEmpty() || employeeId == null) {
            return Collections.emptyMap();
        }
        LambdaQueryWrapper<PerformanceFact> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PerformanceFact::getFactType, FactType.PERF_EXPECT)
            .eq(PerformanceFact::getFactStatus, FactStatus.ACTIVE)
            .eq(PerformanceFact::getEmployeeId, employeeId)
            .and(w -> w.in(PerformanceFact::getOrderNo, bizKeys)
                .or().in(PerformanceFact::getContractNo, bizKeys))
            .select(PerformanceFact::getOrderNo, PerformanceFact::getContractNo,
                PerformanceFact::getPerformanceAmount);
        java.util.Set<String> wanted = new java.util.HashSet<>(bizKeys);
        Map<String, BigDecimal> result = new HashMap<>();
        for (PerformanceFact f : factMapper.selectList(wrapper)) {
            BigDecimal amt = f.getPerformanceAmount() == null ? BigDecimal.ZERO : f.getPerformanceAmount();
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
    public List<PerformanceContractSummaryDTO> listApprovedApplies(Long deptId, Long employeeId, String keyword, String bizType) {
        // 实收审批单行全部落在实收域；performance 域无 PERF_REAL 数据，端口缺失时返回空
        ReceivedRealFactPort port = realPort();
        return port == null ? Collections.emptyList() : port.listApprovedApplies(deptId, employeeId, keyword, bizType);
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

    /**
     * 「是否已调整」判定（2026-10-15 结佣发起闸门用）：业务键 ACTIVE 事实的
     * source_key 链存在 REVERSED 行即 true；PERF_REAL 委托实收域，其余查 pj_perf_fact。
     */
    @Override
    public boolean hasReversedChain(java.util.Collection<String> bizKeys, String factType) {
        if (bizKeys == null || bizKeys.isEmpty()) {
            return false;
        }
        if (isReal(factType)) {
            ReceivedRealFactPort port = realPort();
            return port != null && port.hasReversedChain(bizKeys);
        }
        return factMapper.existsReversedChainByKeys(factType, bizKeys) > 0;
    }

    /**
     * 「是否有在途新签调整单」判定（2026-10-17 结佣发起闸门补强用）：
     * pj_perf_adjust 状态 SUBMITTED/APPROVED（进行中未执行）且业务键命中合同号/订单号即 true。
     */
    @Override
    public boolean hasInflightExpectAdjust(java.util.Collection<String> bizKeys) {
        if (bizKeys == null || bizKeys.isEmpty()) {
            return false;
        }
        return adjustMapper.selectCount(new LambdaQueryWrapper<PerformanceAdjust>()
            .in(PerformanceAdjust::getStatus, AdjustStatus.SUBMITTED, AdjustStatus.APPROVED)
            .and(w -> w.in(PerformanceAdjust::getContractNo, bizKeys)
                .or().in(PerformanceAdjust::getOrderNo, bizKeys))) > 0;
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

    // ==================== 调整执行：事实变更（同 PerformanceAdjustServiceImpl 口径） ====================

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

    /**
     * 解析待冲销事实：若 factId 仍 ACTIVE 则直接返回；
     * 若已被其他操作冲销（REVERSED），按身份键（员工+角色+业务类型+费项+业务日期+期间+合同）
     * 定位唯一 ACTIVE 后继行自动续接。
     * <p>
     * 典型场景：调整审批期间，同一合同另一张调整单抢先执行、把明细绑定的事实冲销并换了 ID；
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
        throw new ServiceException("执行失败：调整单绑定的业绩事实已被其他调整替换且无法唯一匹配当前明细"
            + "（候选 " + successors.size() + " 条），请撤回该调整单后按最新明细重新发起：factId=" + factId);
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
