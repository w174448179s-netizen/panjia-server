package com.panjia.commission.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.commission.domain.ApplicationStatus;
import com.panjia.commission.domain.CommissionApplication;
import com.panjia.commission.domain.CommissionItem;
import com.panjia.commission.domain.ItemStatus;
import com.panjia.commission.mapper.CommissionApplicationMapper;
import com.panjia.commission.mapper.CommissionItemMapper;
import com.panjia.contracts.dto.HistoryRealFactDTO;
import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.ImportBatchArchivedEvent;
import com.panjia.contracts.port.CommissionPerformanceQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 历史工资归档事件处理器（结佣域消费自己的数据）。
 * <p>
 * 消费 HISTORY_PAYROLL 批次归档事件，按合同（业务键=订单号优先，空回退合同号/
 * sourceKey）创建 LOCKED 结佣申请单 + 每实收事实一行 APPROVED 明细（对齐审批回调
 * PENDING→APPROVED + approved_month 口径）。语义同老导入器 ensureCommissionApplications：
 * <ul>
 *   <li>仅取业绩域经端口提供的该批次 ACTIVE PERF_REAL 事实，金额非 0 才入组；</li>
 *   <li>同期间同合同已有未完结申请单（DRAFT/SUBMITTED/APPROVED/LOCKED）→ 挂靠补明细
 *       并收敛为 LOCKED（total_amount/item_count 累加，approved_month/lock_time/approve_time
 *       COALESCE 保留原值）；</li>
 *   <li>否则新建 LOCKED 单（无流程实例，历史补录语义），expected_amount 取该合同
 *       PERF_EXPECT 合计。</li>
 * </ul>
 * 幂等：已绑定非 REVERSED 结佣明细的事实跳过（重导/重消费安全）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommissionArchiveHandler implements DomainEventHandler {

    private static final DateTimeFormatter STAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final CommissionPerformanceQueryPort performanceQueryPort;
    private final CommissionApplicationMapper applicationMapper;
    private final CommissionItemMapper itemMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return ImportBatchArchivedEvent.EVENT_TYPE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(String eventId, String payloadJson) {
        ImportBatchArchivedEvent event;
        try {
            event = objectMapper.readValue(payloadJson, ImportBatchArchivedEvent.class);
        } catch (JacksonException e) {
            log.error("[结佣-历史导入] 归档事件反序列化失败：eventId={}", eventId, e);
            return;
        }
        if (!"HISTORY_PAYROLL".equals(event.getSourceType()) || StringUtils.isBlank(event.getPeriod())) {
            return;
        }
        String period = event.getPeriod().trim();
        log.info("[结佣-历史导入] 开始建 LOCKED 单：batchId={}, period={}", event.getBatchId(), period);

        // ===== 1. 拉取该批次实收事实，金额非 0 入组（业务键分组） =====
        List<HistoryRealFactDTO> facts = performanceQueryPort.listRealFactsByBatch(period, event.getBatchId());
        Map<String, List<HistoryRealFactDTO>> groups = new LinkedHashMap<>();
        for (HistoryRealFactDTO f : facts) {
            if (f.getAmount() == null || f.getAmount().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            groups.computeIfAbsent(bizKeyOf(f), k -> new ArrayList<>()).add(f);
        }
        if (groups.isEmpty()) {
            log.info("[结佣-历史导入] 无待建明细的实收事实：period={}", period);
            return;
        }

        // ===== 2. 幂等过滤：已绑定非 REVERSED 明细的事实跳过 =====
        Set<Long> boundFactIds = findBoundFactIds(facts);
        groups.values().forEach(rows ->
            rows.removeIf(f -> boundFactIds.contains(f.getFactId())));
        groups.values().removeIf(List::isEmpty);
        if (groups.isEmpty()) {
            log.info("[结佣-历史导入] 实收事实均已绑定结佣明细，跳过：period={}", period);
            return;
        }

        // ===== 3. 应收合计（expected_amount） =====
        Set<String> bizKeys = groups.keySet();
        Map<String, BigDecimal> expectedMap =
            performanceQueryPort.sumExpectAmountsByKeys(period, bizKeys);

        LocalDateTime now = LocalDateTime.now();
        String stamp = now.format(STAMP_FORMATTER);
        int created = 0;
        int seq = 1;
        for (Map.Entry<String, List<HistoryRealFactDTO>> entry : groups.entrySet()) {
            String contractNo = entry.getKey();
            List<HistoryRealFactDTO> rows = entry.getValue();
            BigDecimal totalAmount = rows.stream()
                .map(HistoryRealFactDTO::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            // 部门取组内唯一 deptId（多门店混合置空，同老导入器）
            Set<Long> deptIds = new HashSet<>();
            rows.forEach(r -> { if (r.getDeptId() != null) { deptIds.add(r.getDeptId()); } });
            Long deptId = deptIds.size() == 1 ? deptIds.iterator().next() : null;
            BigDecimal expected = expectedMap.getOrDefault(contractNo, BigDecimal.ZERO);
            HistoryRealFactDTO head = rows.get(0);

            // 已有未完结申请单则挂靠补明细，否则新建 LOCKED 单
            CommissionApplication existing = applicationMapper.selectOne(
                new LambdaQueryWrapper<CommissionApplication>()
                    .eq(CommissionApplication::getPeriod, period)
                    .eq(CommissionApplication::getContractNo, contractNo)
                    .in(CommissionApplication::getStatus, ApplicationStatus.DRAFT, ApplicationStatus.SUBMITTED,
                        ApplicationStatus.APPROVED, ApplicationStatus.LOCKED)
                    .last("LIMIT 1"));
            Long applicationId;
            if (existing != null) {
                applicationId = existing.getId();
                existing.setStatus(ApplicationStatus.LOCKED);
                existing.setCurrentNode(null);
                if (existing.getApprovedMonth() == null) {
                    existing.setApprovedMonth(period);
                }
                if (existing.getLockTime() == null) {
                    existing.setLockTime(now);
                }
                if (existing.getApproveTime() == null) {
                    existing.setApproveTime(now);
                }
                existing.setTotalAmount(nvl(existing.getTotalAmount()).add(totalAmount));
                existing.setItemCount((existing.getItemCount() == null ? 0 : existing.getItemCount()) + rows.size());
                applicationMapper.updateById(existing);
                log.info("[结佣-历史导入] 挂靠既有单补明细：applicationId={}, contract={}", applicationId, contractNo);
            } else {
                CommissionApplication app = new CommissionApplication();
                app.setApplyNo("CAPP" + stamp + String.format("%03d", seq++));
                app.setPeriod(period);
                app.setContractNo(contractNo);
                app.setOrderNo(contractNo);
                app.setPropertyAddress(head.getPropertyAddress());
                app.setBusinessDate(maxBusinessDate(rows));
                app.setDeptId(deptId);
                app.setItemCount(rows.size());
                app.setTotalAmount(totalAmount);
                app.setExpectedAmount(expected);
                app.setAligned(false);
                app.setStatus(ApplicationStatus.LOCKED);
                app.setApprovedMonth(period);
                app.setLockTime(now);
                app.setApproveTime(now);
                applicationMapper.insert(app);
                applicationId = app.getId();
                created++;
                log.info("[结佣-历史导入] 新建 LOCKED 单：applyNo={}, contract={}, total={}, items={}",
                    app.getApplyNo(), contractNo, totalAmount, rows.size());
            }
            // 每条事实一行 APPROVED 明细（对齐审批回调：PENDING→APPROVED + approved_month）
            for (HistoryRealFactDTO f : rows) {
                CommissionItem item = new CommissionItem();
                item.setApplicationId(applicationId);
                item.setPerformanceFactId(f.getFactId());
                item.setPeriod(period);
                item.setContractNo(contractNo);
                item.setApprovedMonth(period);
                item.setEmployeeId(f.getEmployeeId());
                item.setDeptId(f.getDeptId());
                item.setBizType(f.getBizType());
                item.setRoleType(f.getRoleType());
                item.setFeeItem(f.getFeeItem());
                item.setAmount(f.getAmount());
                item.setStatus(ItemStatus.APPROVED);
                item.setOriginReversed(false);
                itemMapper.insert(item);
            }
        }
        log.info("[结佣-历史导入] 完成：新建 {} 张（period={}）", created, period);
    }

    /** 批量查已绑定结佣明细的事实 ID（status != REVERSED） */
    private Set<Long> findBoundFactIds(List<HistoryRealFactDTO> facts) {
        List<Long> factIds = facts.stream().map(HistoryRealFactDTO::getFactId).distinct().toList();
        Set<Long> bound = new HashSet<>();
        if (factIds.isEmpty()) {
            return bound;
        }
        // 分批防 IN 超长
        int batchSize = 500;
        for (int i = 0; i < factIds.size(); i += batchSize) {
            List<Long> batch = factIds.subList(i, Math.min(factIds.size(), i + batchSize));
            List<CommissionItem> items = itemMapper.selectList(new LambdaQueryWrapper<CommissionItem>()
                .in(CommissionItem::getPerformanceFactId, batch)
                .ne(CommissionItem::getStatus, ItemStatus.REVERSED)
                .select(CommissionItem::getPerformanceFactId));
            items.forEach(it -> bound.add(it.getPerformanceFactId()));
        }
        return bound;
    }

    /** 业务键：订单号优先，空回退合同号，再回退 sourceKey（同老导入器 bizKeyOf） */
    private String bizKeyOf(HistoryRealFactDTO f) {
        if (StringUtils.isNotBlank(f.getOrderNo())) {
            return f.getOrderNo();
        }
        if (StringUtils.isNotBlank(f.getContractNo())) {
            return f.getContractNo();
        }
        return f.getSourceKey();
    }

    /** 组内最晚业务日期（历史行业务日期为签约日，兜底空值跳过） */
    private LocalDateTime maxBusinessDate(List<HistoryRealFactDTO> rows) {
        LocalDateTime max = null;
        for (HistoryRealFactDTO f : rows) {
            if (f.getBusinessDate() != null) {
                LocalDateTime dt = f.getBusinessDate().atStartOfDay();
                if (max == null || dt.isAfter(max)) {
                    max = dt;
                }
            }
        }
        return max;
    }

    private BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
