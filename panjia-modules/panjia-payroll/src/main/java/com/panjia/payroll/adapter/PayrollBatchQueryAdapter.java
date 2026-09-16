package com.panjia.payroll.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.port.PayrollBatchQueryPort;
import com.panjia.payroll.domain.BatchStatus;
import com.panjia.payroll.domain.PayrollBatch;
import com.panjia.payroll.mapper.PayrollBatchMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 算薪批次跨域查询适配器。
 * <p>
 * 实现 {@link PayrollBatchQueryPort}，供业绩域在作废/恢复事实前校验。
 */
@Component
@RequiredArgsConstructor
public class PayrollBatchQueryAdapter implements PayrollBatchQueryPort {

    private final PayrollBatchMapper batchMapper;

    @Override
    public boolean hasActiveBatch(String period) {
        Long count = batchMapper.selectCount(new LambdaQueryWrapper<PayrollBatch>()
            .eq(PayrollBatch::getPeriod, period)
            .in(PayrollBatch::getStatus, List.of(
                BatchStatus.CALCULATED,
                BatchStatus.REVIEWING,
                BatchStatus.APPROVED)));
        return count != null && count > 0;
    }
}
