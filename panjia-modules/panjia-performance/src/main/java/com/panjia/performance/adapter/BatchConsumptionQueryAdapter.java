package com.panjia.performance.adapter;

import com.panjia.contracts.port.BatchConsumptionQueryPort;
import com.panjia.performance.domain.PerformanceAdjust;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.ReceivedApply;
import com.panjia.performance.domain.ReceivedApplyStatus;
import com.panjia.performance.mapper.PerformanceAdjustMapper;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.mapper.ReceivedApplyMapper;
import com.panjia.performance.service.PeriodCloseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 批次消费状态查询端口实现（入站适配器）。
 * <p>
 * 定义在 panjia-contracts，由业绩域实现，供导入域撤销批次前调用。
 * 校验三项不可撤销条件：
 * <ol>
 *   <li>期间已封账</li>
 *   <li>存在业绩调整单（只要存在就不能删：调整单一创建即走流程，硬删会产生流程孤儿）</li>
 *   <li>存在已审批通过的实收确认单（APPROVED：已生效永不回退；审批中可连同流程一起硬删）</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class BatchConsumptionQueryAdapter implements BatchConsumptionQueryPort {

    private final PeriodCloseService periodCloseService;
    private final PerformanceFactMapper factMapper;
    private final PerformanceAdjustMapper adjustMapper;
    private final ReceivedApplyMapper receivedApplyMapper;

    @Override
    public RevokeCheckResult checkRevocable(Long batchId, String period) {
        // 校验①：期间已封账
        if (period != null && periodCloseService.isClosed(period)) {
            return RevokeCheckResult.reject("期间[" + period + "]已封账，禁止撤销");
        }

        // 校验②：存在业绩调整单（只要存在就不能删：调整单一创建即走流程，硬删会产生流程孤儿）
        // adjust → fact → batch，通过 fact_id 关联
        List<Long> factIds = factMapper.selectObjs(
            new LambdaQueryWrapper<PerformanceFact>()
                .select(PerformanceFact::getId)
                .eq(PerformanceFact::getBatchId, batchId))
            .stream()
            .map(o -> ((Number) o).longValue())
            .toList();
        if (!factIds.isEmpty()) {
            Long adjustCount = adjustMapper.selectCount(
                new LambdaQueryWrapper<PerformanceAdjust>()
                    .in(PerformanceAdjust::getFactId, factIds));
            if (adjustCount != null && adjustCount > 0) {
                return RevokeCheckResult.reject(
                    "该批次存在业绩调整单（" + adjustCount + " 条），禁止撤销，请先处理调整单");
            }
        }

        // 校验③：存在已审批通过的实收确认单
        // APPROVED 已生效永不回退；SUBMITTED/审批中可连同流程实例一起硬删（handler 已处理），不会留孤儿
        LambdaQueryWrapper<ReceivedApply> approvedQuery = new LambdaQueryWrapper<>();
        approvedQuery.eq(ReceivedApply::getBatchId, batchId)
            .eq(ReceivedApply::getStatus, ReceivedApplyStatus.APPROVED);
        Long approvedCount = receivedApplyMapper.selectCount(approvedQuery);
        if (approvedCount != null && approvedCount > 0) {
            return RevokeCheckResult.reject(
                "该批次存在已审批通过的实收确认单（" + approvedCount + " 条），禁止撤销，请走退单/调整流程");
        }

        return RevokeCheckResult.ok();
    }
}
