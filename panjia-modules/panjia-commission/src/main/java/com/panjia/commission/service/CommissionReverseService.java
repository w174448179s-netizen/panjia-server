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
 *   <li>PENDING/DRAFT（未审批）→ 随动作废 REVERSED，reversed_reason = 事件 reason；
 *       所属 SUBMITTED 单由 {@code revertSubmittedToDraftIfNeeded} 终止审批流程 + 回退 DRAFT
 *       + 按调整后新签金额重建明细；DRAFT 单直接重建明细；</li>
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

        // 分治①：未审批明细（DRAFT 待提交 / PENDING 待审批）随动作废
        List<Long> pendingIds = affectedItems.stream()
            .filter(i -> i.getStatus() == ItemStatus.DRAFT || i.getStatus() == ItemStatus.PENDING)
            .map(CommissionItem::getId)
            .toList();
        if (!pendingIds.isEmpty()) {
            itemMapper.update(null, new LambdaUpdateWrapper<CommissionItem>()
                .in(CommissionItem::getId, pendingIds)
                .in(CommissionItem::getStatus, ItemStatus.DRAFT, ItemStatus.PENDING)
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

        // 聚合重算 + 按单状态分治联动：
        //   DRAFT 单：明细被冲销后从当前新签事实重建（rebuildItemsIfNeeded）；
        //   SUBMITTED 单：终止审批流程 + 回退 DRAFT + 按调整后新签金额重建（revertSubmittedToDraftIfNeeded）；
        //   APPROVED/LOCKED 单：明细金额不动（仅 origin_reversed 标记），不重建。
        Set<Long> applicationIds = affectedItems.stream()
            .map(CommissionItem::getApplicationId)
            .collect(Collectors.toSet());
        for (Long applicationId : applicationIds) {
            // DRAFT 单部分明细被冲销（增加角色人只 supersede 被扣除行）时，连带作废同单其余
            // 未审批明细并按当前 ACTIVE 新签事实整单重建（原因写入 reversed_reason）
            applicationService.revertSubmittedToDraftIfNeeded(applicationId, reversedReason);
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
