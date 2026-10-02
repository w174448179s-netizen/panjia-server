package com.panjia.performance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.event.PeriodReopenedEvent;
import com.panjia.performance.domain.IllegalStateTransitionException;
import com.panjia.performance.domain.PerformancePeriodClose;
import com.panjia.performance.domain.PeriodCloseStatus;
import com.panjia.performance.mapper.PerformancePeriodCloseMapper;
import com.panjia.performance.service.IPeriodCloseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 期间封账服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PeriodCloseServiceImpl implements IPeriodCloseService {

    /** 系统操作人占位 ID：pj_perf_period_close.operator_id 为 NOT NULL，无人工上下文的自动动作用 0 */
    private static final Long SYSTEM_OPERATOR_ID = 0L;

    private final PerformancePeriodCloseMapper periodCloseMapper;
    private final ApplicationEventPublisher eventPublisher;

    /** 按期间取封账记录（内部使用；无记录返回 null） */
    private PerformancePeriodClose getPeriod(String period) {
        if (StringUtils.isBlank(period)) {
            return null;
        }
        return periodCloseMapper.selectOne(
            new LambdaQueryWrapper<PerformancePeriodClose>()
                .eq(PerformancePeriodClose::getPeriod, period));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void closePeriod(String period, String reason, Long operatorId) {
        if (StringUtils.isBlank(period)) {
            throw new ServiceException("期间不能为空");
        }

        // operator_id NOT NULL：系统自动调用且未携带操作人时兜底系统账号
        Long effectiveOperatorId = operatorId != null ? operatorId : SYSTEM_OPERATOR_ID;

        PerformancePeriodClose record = getPeriod(period);
        if (record == null) {
            // 不存在则先创建（OPEN 状态）
            record = new PerformancePeriodClose();
            record.setPeriod(period);
            record.setStatus(PeriodCloseStatus.OPEN);
            record.setOperatorId(effectiveOperatorId);
            periodCloseMapper.insert(record);
        }

        // 校验状态可流转
        if (!record.getStatus().canTransitTo(PeriodCloseStatus.CLOSED)) {
            throw new IllegalStateTransitionException(
                record.getStatus().getCode(), PeriodCloseStatus.CLOSED.getCode(), "期间封账");
        }

        // 执行封账
        record.setStatus(PeriodCloseStatus.CLOSED);
        record.setCloseReason(reason);
        record.setOperatorId(effectiveOperatorId);
        record.setCloseTime(LocalDateTime.now());
        periodCloseMapper.updateById(record);

        log.info("[期间封账] 封账完成：period={}, operatorId={}", period, operatorId);
    }

    @Override
    public void reopenPeriod(String period, String reason, Long operatorId) {
        if (StringUtils.isBlank(period)) {
            throw new ServiceException("期间不能为空");
        }
        if (StringUtils.isBlank(reason)) {
            // §3.5 反结账需强制录入原因，留痕审计
            throw new ServiceException("反结账必须填写原因（留痕审计）");
        }

        PerformancePeriodClose record = getPeriod(period);
        if (record == null) {
            throw new ServiceException("期间记录不存在：period={}", period);
        }

        // 校验状态可流转（CLOSED → OPEN）
        if (record.getStatus() != PeriodCloseStatus.CLOSED) {
            throw new IllegalStateTransitionException(
                record.getStatus().getCode(), PeriodCloseStatus.OPEN.getCode(), "期间封账");
        }

        // 保留原封账原因 + 追加反结账原因/操作人/时间，用于审计（§3.5 留痕）
        String originalCloseReason = record.getCloseReason();
        String reopenMark = "[反结账 " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            + " 操作人=" + operatorId + " 原因=" + reason.trim() + "]";
        String auditReason = StringUtils.isBlank(originalCloseReason)
            ? reopenMark
            : originalCloseReason + " | " + reopenMark;

        // 执行反结账
        record.setStatus(PeriodCloseStatus.OPEN);
        record.setCloseTime(null);
        record.setOperatorId(operatorId);
        record.setCloseReason(auditReason);
        periodCloseMapper.updateById(record);

        // 方案 B：解封联动解锁工资批次。
        // 同步发布 PeriodReopenedEvent，panjia-payroll 监听器在同一事务内把该期间
        // 所有 LOCKED 批次解锁回 CALCULATED，使用户可重算 → 重审 → 再锁。
        // 监听器异常会回滚本事务，保证期间与工资批次状态一致（不会出现"期间开了但工资还锁着"）。
        PeriodReopenedEvent event = new PeriodReopenedEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setPeriod(period);
        event.setReason(reason.trim());
        event.setOperatorId(operatorId);
        eventPublisher.publishEvent(event);

        log.info("[期间封账] 反结账完成：period={}, operatorId={}, reason={}", period, operatorId, reason);
    }

    @Override
    public boolean isClosed(String period) {
        if (StringUtils.isBlank(period)) {
            return false;
        }
        PerformancePeriodClose record = getPeriod(period);
        return record != null && record.getStatus() == PeriodCloseStatus.CLOSED;
    }
}
