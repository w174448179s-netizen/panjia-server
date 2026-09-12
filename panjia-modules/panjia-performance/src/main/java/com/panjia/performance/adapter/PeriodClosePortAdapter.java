package com.panjia.performance.adapter;

import com.panjia.contracts.port.PeriodCloseQueryPort;
import com.panjia.performance.service.PeriodCloseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 期间封账跨域查询适配器（panjia-performance 实现 contracts {@link PeriodCloseQueryPort}）。
 * <p>
 * 委托业绩域 {@link PeriodCloseService#isClosed(String)}（pj_perf_period_close 为唯一封账事实源，
 * 结佣域不建封账表，CI C9）。
 */
@Service
@RequiredArgsConstructor
public class PeriodClosePortAdapter implements PeriodCloseQueryPort {

    private final PeriodCloseService periodCloseService;

    @Override
    public boolean isClosed(String period) {
        return periodCloseService.isClosed(period);
    }
}
