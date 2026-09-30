package com.panjia.performance.adapter;

import com.panjia.contracts.port.BatchConsumptionQueryPort;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceAdjust;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.ReceivedApply;
import com.panjia.performance.domain.ReceivedApplyStatus;
import com.panjia.performance.mapper.PerformanceAdjustMapper;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.mapper.ReceivedApplyMapper;
import com.panjia.performance.service.IPeriodCloseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 批次消费状态查询端口实现（入站适配器）。
 * <p>
 * 定义在 panjia-contracts，由业绩域实现，供导入域撤销批次前调用。
 * 校验四项不可撤销条件：
 * <ol>
 *   <li>期间已封账</li>
 *   <li>存在业绩调整单（一创建即走流程，硬删会产生流程孤儿）</li>
 *   <li>存在审批中(SUBMITTED)/已通过(APPROVED)的实收确认单——工作流进行中或已生效，
 *       禁止撤销；DRAFT(无新签等待中)无工作流实例，允许撤销硬删</li>
 *   <li>新签批次反查同订单是否存在 SUBMITTED/APPROVED 实收单——新签可能已触发下游
 *       实收审批/通过(resolveDraftAfterNewSign)，撤销会让下游实收单+结佣单成为孤儿</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class BatchConsumptionQueryAdapter implements BatchConsumptionQueryPort {

    private final IPeriodCloseService periodCloseService;
    private final PerformanceFactMapper factMapper;
    private final PerformanceAdjustMapper adjustMapper;
    private final ReceivedApplyMapper receivedApplyMapper;

    @Override
    public RevokeCheckResult checkRevocable(Long batchId, String period) {
        // 校验①：期间已封账
        if (period != null && periodCloseService.isClosed(period)) {
            return RevokeCheckResult.reject("期间[" + period + "]已封账，禁止撤销");
        }

        // 查该批次全部事实（校验②④复用）
        List<PerformanceFact> facts = factMapper.selectList(
            new LambdaQueryWrapper<PerformanceFact>()
                .eq(PerformanceFact::getBatchId, batchId));
        List<Long> factIds = facts.stream().map(PerformanceFact::getId).toList();

        // 校验②：存在业绩调整单（一创建即走流程，硬删会产生流程孤儿）
        // adjust → fact → batch，通过 fact_id 关联
        if (!factIds.isEmpty()) {
            Long adjustCount = adjustMapper.selectCount(
                new LambdaQueryWrapper<PerformanceAdjust>()
                    .in(PerformanceAdjust::getFactId, factIds));
            if (adjustCount != null && adjustCount > 0) {
                return RevokeCheckResult.reject(
                    "该批次存在业绩调整单（" + adjustCount + " 条），禁止撤销，请先处理调整单");
            }
        }

        // 校验③：实收批次存在审批中(SUBMITTED)/已通过(APPROVED)实收确认单 → 禁止
        // DRAFT(无新签等待中)无工作流实例，允许撤销硬删（ReceivedRevokedHandler 直接删）
        LambdaQueryWrapper<ReceivedApply> activeApplyQuery = new LambdaQueryWrapper<>();
        activeApplyQuery.eq(ReceivedApply::getBatchId, batchId)
            .in(ReceivedApply::getStatus, ReceivedApplyStatus.SUBMITTED, ReceivedApplyStatus.APPROVED);
        Long activeApplyCount = receivedApplyMapper.selectCount(activeApplyQuery);
        if (activeApplyCount != null && activeApplyCount > 0) {
            return RevokeCheckResult.reject(
                "该批次存在审批中/已通过的实收确认单（" + activeApplyCount + " 条），禁止撤销，请走退单/调整流程");
        }

        // 校验④：新签批次反查同订单是否有 SUBMITTED/APPROVED 实收单
        // 新签可能已触发下游实收审批/通过（resolveDraftAfterNewSign），撤销会让下游+结佣单成孤儿
        List<String> newSignOrderNos = facts.stream()
            .filter(f -> FactType.PERF_EXPECT.equals(f.getFactType()))
            .map(PerformanceFact::getOrderNo)
            .filter(s -> s != null && !s.isEmpty())
            .distinct()
            .toList();
        if (!newSignOrderNos.isEmpty()) {
            Long downstreamCount = receivedApplyMapper.selectCount(
                new LambdaQueryWrapper<ReceivedApply>()
                    .in(ReceivedApply::getOrderNo, newSignOrderNos)
                    .in(ReceivedApply::getStatus, ReceivedApplyStatus.SUBMITTED, ReceivedApplyStatus.APPROVED));
            if (downstreamCount != null && downstreamCount > 0) {
                return RevokeCheckResult.reject(
                    "该新签批次已触发下游实收审批/通过（" + downstreamCount + " 条），禁止撤销，请先走退单/调整流程");
            }
        }

        return RevokeCheckResult.ok();
    }
}
