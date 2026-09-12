package com.panjia.commission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.panjia.commission.domain.CommissionConsumeLog;
import com.panjia.commission.domain.CommissionItem;
import com.panjia.commission.domain.ConsumeStatus;
import com.panjia.commission.domain.ItemStatus;
import com.panjia.commission.domain.ReversedReason;
import com.panjia.commission.mapper.CommissionConsumeLogMapper;
import com.panjia.commission.mapper.CommissionItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 结佣冲销联动服务（消费 PerformanceFactReversedEvent，结佣域详细设计 §4.4 分治表）。
 * <p>
 * 分治规则：
 * <ul>
 *   <li>PENDING（未审批）→ 随动作废 REVERSED，reversed_reason = 事件 reason；</li>
 *   <li>APPROVED（已审批）→ <b>金额一动不动</b>，仅置 origin_reversed = true + 告警
 *       （V4.2 §9.2-4：已审批数据永不覆盖，是否调整由人工走 DIFF 调整单决定）；</li>
 *   <li>REVERSED（已冲销）→ 忽略（终态）。</li>
 * </ul>
 * <p>
 * 幂等：uk_ccl_event(event_id) 唯一索引 + 重复投递先查日志跳过。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionReverseService {

    /** 事件类型常量（消费日志 event_type 取值） */
    public static final String EVENT_TYPE_FACT_REVERSED = "FACT_REVERSED";
    public static final String EVENT_TYPE_FACT_CREATED = "FACT_CREATED";

    private final CommissionItemMapper itemMapper;
    private final CommissionConsumeLogMapper consumeLogMapper;
    private final CommissionApplicationService applicationService;

    /**
     * 处理业绩事实冲销事件。
     *
     * @param eventId  事件唯一 ID（幂等锚点）
     * @param period   归属期间（事件携带，可空；以明细自身 period 为准）
     * @param factIds  被冲销的事实 ID 列表（String 形式）
     * @param reason   冲销原因 code（SUPERSEDE / RENORMALIZE / MANUAL_ADJUST / PERIOD_VOID）
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleReversed(String eventId, String period, List<String> factIds, String reason) {
        if (factIds == null || factIds.isEmpty()) {
            log.info("[结佣-冲销联动] 事件无事实 ID，忽略：eventId={}", eventId);
            return;
        }

        // 幂等：同一事件重复投递直接跳过（uk_ccl_event 兜底）
        Long existed = consumeLogMapper.selectCount(new LambdaQueryWrapper<CommissionConsumeLog>()
            .eq(CommissionConsumeLog::getEventId, eventId));
        if (existed != null && existed > 0) {
            log.info("[结佣-冲销联动] 事件已消费，跳过：eventId={}", eventId);
            return;
        }

        List<Long> ids = factIds.stream()
            .map(Long::valueOf)
            .toList();

        // 查受影响明细（未 REVERSED）
        List<CommissionItem> affectedItems = itemMapper.selectList(new LambdaQueryWrapper<CommissionItem>()
            .in(CommissionItem::getPerformanceFactId, ids)
            .ne(CommissionItem::getStatus, ItemStatus.REVERSED));

        ReversedReason reversedReason = ReversedReason.fromCode(reason);
        if (reversedReason == null) {
            log.warn("[结佣-冲销联动] 未知冲销原因，按 MANUAL_ADJUST 兜底：eventId={}, reason={}", eventId, reason);
            reversedReason = ReversedReason.MANUAL_ADJUST;
        }

        // 分治①：PENDING 随动作废
        List<Long> pendingIds = affectedItems.stream()
            .filter(i -> i.getStatus() == ItemStatus.PENDING)
            .map(CommissionItem::getId)
            .toList();
        if (!pendingIds.isEmpty()) {
            itemMapper.update(null, new LambdaUpdateWrapper<CommissionItem>()
                .in(CommissionItem::getId, pendingIds)
                .eq(CommissionItem::getStatus, ItemStatus.PENDING)
                .set(CommissionItem::getStatus, ItemStatus.REVERSED)
                .set(CommissionItem::getReversedReason, reversedReason));
        }

        // 分治②：APPROVED 金额不动，仅标记 origin_reversed + 告警
        List<CommissionItem> approvedItems = affectedItems.stream()
            .filter(i -> i.getStatus() == ItemStatus.APPROVED)
            .toList();
        for (CommissionItem item : approvedItems) {
            log.warn("[结佣-冲销联动] ★ 已审批明细的源业绩被冲销，金额保持不动（V4.2 §9.2-4），"
                + "是否补差请人工走 DIFF 调整单：itemId={}, amount={}, factId={}, reason={}",
                item.getId(), item.getAmount(), item.getPerformanceFactId(), reason);
        }
        if (!approvedItems.isEmpty()) {
            itemMapper.update(null, new LambdaUpdateWrapper<CommissionItem>()
                .in(CommissionItem::getId, approvedItems.stream().map(CommissionItem::getId).toList())
                .eq(CommissionItem::getStatus, ItemStatus.APPROVED)
                .set(CommissionItem::getOriginReversed, true));
        }

        // 聚合重算（PENDING 作废影响 total_amount；APPROVED 标记不影响金额但重算无害）
        Set<Long> applicationIds = affectedItems.stream()
            .map(CommissionItem::getApplicationId)
            .collect(Collectors.toSet());
        for (Long applicationId : applicationIds) {
            applicationService.recalcAggregates(applicationId, null);
        }

        // 写消费日志（幂等锚点 + 留痕）
        CommissionConsumeLog consumeLog = new CommissionConsumeLog();
        consumeLog.setEventId(eventId);
        consumeLog.setEventType(EVENT_TYPE_FACT_REVERSED);
        consumeLog.setPeriod(period);
        consumeLog.setFactIds(String.join(",", factIds));
        consumeLog.setFactCount(factIds.size());
        consumeLog.setAffectedItems(affectedItems.size());
        consumeLog.setStatus(ConsumeStatus.SUCCESS);
        consumeLog.setMessage("PENDING作废 " + pendingIds.size() + " 条，APPROVED标记 " + approvedItems.size() + " 条");
        consumeLogMapper.insert(consumeLog);

        log.info("[结佣-冲销联动] 处理完成：eventId={}, factCount={}, PENDING作废={}, APPROVED标记={}",
            eventId, factIds.size(), pendingIds.size(), approvedItems.size());
    }

    /**
     * 记录事实创建事件的消费留痕（不自动建明细，进工资必须经人工发起 + 审批）。
     *
     * @param eventId 事件唯一 ID（幂等锚点）
     * @param period  归属期间
     * @param factIds 事实 ID 列表
     * @param message 留痕说明
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordFactCreated(String eventId, String period, List<String> factIds, String message) {
        Long existed = consumeLogMapper.selectCount(new LambdaQueryWrapper<CommissionConsumeLog>()
            .eq(CommissionConsumeLog::getEventId, eventId));
        if (existed != null && existed > 0) {
            log.info("[结佣-事实创建] 事件已消费，跳过：eventId={}", eventId);
            return;
        }
        CommissionConsumeLog consumeLog = new CommissionConsumeLog();
        consumeLog.setEventId(eventId);
        consumeLog.setEventType(EVENT_TYPE_FACT_CREATED);
        consumeLog.setPeriod(period);
        consumeLog.setFactIds(String.join(",", factIds));
        consumeLog.setFactCount(factIds.size());
        consumeLog.setAffectedItems(0);
        consumeLog.setStatus(ConsumeStatus.SUCCESS);
        consumeLog.setMessage(message);
        consumeLogMapper.insert(consumeLog);
    }
}
