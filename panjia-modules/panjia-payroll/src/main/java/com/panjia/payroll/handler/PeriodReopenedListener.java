package com.panjia.payroll.handler;

import com.panjia.contracts.event.PeriodReopenedEvent;
import com.panjia.payroll.service.PayrollBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 业绩期间解封（反结账）事件监听器。
 * <p>
 * 监听 {@code panjia-performance} 发布的 {@link PeriodReopenedEvent}，
 * 在<b>同一事务内</b>把该期间所有 LOCKED 状态的工资批次解锁回 CALCULATED，
 * 使用户可重新算薪 → 重新提交审批 → 再次锁定（方案 B：解封联动解锁）。
 * <p>
 * 监听器异常不吞，向上传播以回滚期间解封事务，保证期间与工资批次状态一致。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PeriodReopenedListener {

    private final PayrollBatchService payrollBatchService;

    @EventListener
    public void onPeriodReopened(PeriodReopenedEvent event) {
        String period = event.getPeriod();
        if (period == null || period.isBlank()) {
            log.warn("[薪酬反结账] PeriodReopenedEvent 缺少 period，跳过解锁：eventId={}", event.getEventId());
            return;
        }
        int unlocked = payrollBatchService.unlockByPeriod(period, event.getReason(), event.getOperatorId());
        log.info("[薪酬反结账] 期间 {} 解锁完成：unlockedBatches={}, eventId={}",
            period, unlocked, event.getEventId());
    }
}
