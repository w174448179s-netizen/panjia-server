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
     *
     * @param event 发薪锁定事件
     */
    @EventListener
    public void onPayrollLocked(PayrollLockedEvent event) {
        try {
            log.info("[期间自动封账] 收到发薪锁定事件：batchId={}", event.getBatchId());
            // TODO: PayrollLockedEvent 当前不含期间字段，需根据 batchId 查询对应期间
            //  或后续增强事件 payload 增加 period 字段
            //  此处暂留占位，待事件 payload 完善后补充期间解析逻辑
            log.warn("[期间自动封账] PayrollLockedEvent 暂未提供期间字段，自动封账逻辑待完善：batchId={}",
                    event.getBatchId());
        } catch (Exception e) {
            log.error("[期间自动封账] 发薪锁定事件处理失败：batchId={}", event.getBatchId(), e);
        }
    }
}
