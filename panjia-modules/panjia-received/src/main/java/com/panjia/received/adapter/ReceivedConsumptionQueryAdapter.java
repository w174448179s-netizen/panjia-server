package com.panjia.received.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.port.ReceivedConsumptionQueryPort;
import com.panjia.received.domain.ReceivedContract;
import com.panjia.received.domain.ReceivedDetail;
import com.panjia.received.mapper.ReceivedContractMapper;
import com.panjia.received.mapper.ReceivedDetailMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 实收消费状态查询端口实现（入站适配器）。
 * <p>
 * 供业绩域撤销批次前置校验调用：提供本批实收合同业务键范围（补全结佣调整单
 * 按合同拦截的口径），以及本批明细被结佣调整 supersede 触碰的行数
 * （adjust_id 非空，含调整后新生效行）。
 */
@Component
@RequiredArgsConstructor
public class ReceivedConsumptionQueryAdapter implements ReceivedConsumptionQueryPort {

    private final ReceivedContractMapper contractMapper;
    private final ReceivedDetailMapper detailMapper;

    @Override
    public BatchReceivedScope loadBatchScope(Long batchId) {
        // 一条查询取本批合同双业务键，内存分流，避免两次 IN 查询
        List<ReceivedContract> contracts = contractMapper.selectList(
            new LambdaQueryWrapper<ReceivedContract>()
                .select(ReceivedContract::getContractNo, ReceivedContract::getOrderNo)
                .eq(ReceivedContract::getBatchId, batchId));
        BatchReceivedScope scope = new BatchReceivedScope();
        scope.setContractNos(contracts.stream()
            .map(ReceivedContract::getContractNo)
            .filter(s -> s != null && !s.isBlank())
            .map(String::trim)
            .distinct()
            .toList());
        scope.setOrderNos(contracts.stream()
            .map(ReceivedContract::getOrderNo)
            .filter(s -> s != null && !s.isBlank())
            .map(String::trim)
            .distinct()
            .toList());
        return scope;
    }

    @Override
    public long countAdjustedDetails(Long batchId) {
        Long count = detailMapper.selectCount(new LambdaQueryWrapper<ReceivedDetail>()
            .eq(ReceivedDetail::getSourceBatchId, batchId)
            .isNotNull(ReceivedDetail::getAdjustId));
        return Objects.requireNonNullElse(count, 0L);
    }
}
