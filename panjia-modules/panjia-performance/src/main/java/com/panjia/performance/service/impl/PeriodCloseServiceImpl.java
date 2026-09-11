package com.panjia.performance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.performance.domain.IllegalStateTransitionException;
import com.panjia.performance.domain.PerformancePeriodClose;
import com.panjia.performance.domain.PeriodCloseStatus;
import com.panjia.performance.mapper.PerformancePeriodCloseMapper;
import com.panjia.performance.service.PeriodCloseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 期间封账服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PeriodCloseServiceImpl implements PeriodCloseService {

    private final PerformancePeriodCloseMapper periodCloseMapper;

    @Override
    public List<PerformancePeriodClose> listPeriods() {
        return periodCloseMapper.selectList(
            new LambdaQueryWrapper<PerformancePeriodClose>()
                .orderByDesc(PerformancePeriodClose::getPeriod));
    }

    @Override
    public PerformancePeriodClose getPeriod(String period) {
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

        PerformancePeriodClose record = getPeriod(period);
        if (record == null) {
            // 不存在则先创建（OPEN 状态）
            record = new PerformancePeriodClose();
            record.setPeriod(period);
            record.setStatus(PeriodCloseStatus.OPEN);
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
        record.setOperatorId(operatorId);
        record.setCloseTime(LocalDateTime.now());
        periodCloseMapper.updateById(record);

        log.info("[期间封账] 封账完成：period={}, operatorId={}", period, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reopenPeriod(String period, Long operatorId) {
        if (StringUtils.isBlank(period)) {
            throw new ServiceException("期间不能为空");
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

        // 执行反结账
        record.setStatus(PeriodCloseStatus.OPEN);
        record.setCloseTime(null);
        record.setOperatorId(operatorId);
        periodCloseMapper.updateById(record);

        log.info("[期间封账] 反结账完成：period={}, operatorId={}", period, operatorId);
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
