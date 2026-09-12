package com.panjia.performance.listener;

import com.panjia.contracts.event.PayrollLockedEvent;
import com.panjia.performance.service.PeriodCloseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 薪资域事件监听器。
 * <p>
 * 监听薪资域发布的发薪锁定事件，自动触发对应期间的业绩封账。
 * 事件监听异常不向上抛出，避免影响主流程。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PayrollEventListener {

    private final PeriodCloseService periodCloseService;

    /**
     * 监听发薪锁定事件，自动封账对应期间。
     * <p>
     * 工资批次 LOCKED 即代表该归属月工资已发，业绩事实与结佣窗口必须同步终态关闭
     * （架构 §2.1 业务事实链 / 结佣域 B16）。Outbox 可能重复投递，{@link PeriodCloseService#isClosed}
     * 提供天然幂等：已 CLOSED 直接跳过，不重复写封账记录。
     *
     * @param event 发薪锁定事件（V1.9.2 §7.3.1 契约，含 period）
     */
    @EventListener
    public void onPayrollLocked(PayrollLockedEvent event) {
        try {
            String period = event.getPeriod();
            if (period == null || period.isBlank()) {
                // 契约升级后 period 必填；防御：缺失期间无法定位封账目标，仅告警不抛异常
                log.warn("[期间自动封账] PayrollLockedEvent 缺少 period，跳过自动封账：eventId={}, batchId={}",
                        event.getEventId(), event.getBatchId());
                return;
            }
            if (periodCloseService.isClosed(period)) {
                log.info("[期间自动封账] 期间已封账，幂等跳过：period={}, eventId={}, batchId={}",
                        period, event.getEventId(), event.getBatchId());
                return;
            }
            periodCloseService.closePeriod(period,
                    "工资批次锁定自动封账 batchId=" + event.getBatchId(), null);
            log.info("[期间自动封账] 自动封账完成：period={}, eventId={}, batchId={}",
                    period, event.getEventId(), event.getBatchId());
        } catch (Exception e) {
            log.error("[期间自动封账] 发薪锁定事件处理失败：eventId={}, batchId={}",
                    event.getEventId(), event.getBatchId(), e);
        }
    }
}
