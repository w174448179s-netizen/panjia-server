package com.panjia.received.listener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.ImportBatchRevokedEvent;
import com.panjia.received.domain.ReceivedContract;
import com.panjia.received.domain.ReceivedDetail;
import com.panjia.performance.domain.ReceivedApply;
import com.panjia.received.mapper.ReceivedContractMapper;
import com.panjia.received.mapper.ReceivedDetailMapper;
import com.panjia.performance.mapper.ReceivedApplyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 导入批次撤销事件处理器（实收域）。
 * <p>
 * KE_RECEIVED / HISTORY_PAYROLL 批次撤销 → 硬删实收合同 + 实收明细 + 关联审批单。
 * （实收表在拆表后已迁出 pj_perf_fact，不再走 PerformanceRevokedHandler）。
 * <p>
 * 审批单状态约定（2026-09-30）：撤销前置校验（BatchConsumptionQueryAdapter 校验③）
 * 已拦截 SUBMITTED(审批中)/APPROVED(已通过) 单，到达此 handler 的审批单均为 DRAFT
 * （无新签等待中，无工作流实例），硬删安全不留孤儿。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReceivedRevokedHandler implements DomainEventHandler {

    private static final String SOURCE_TYPE_KE_RECEIVED = "KE_RECEIVED";
    private static final String SOURCE_TYPE_HISTORY_PAYROLL = "HISTORY_PAYROLL";

    private final ReceivedContractMapper contractMapper;
    private final ReceivedDetailMapper detailMapper;
    private final ReceivedApplyMapper applyMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return ImportBatchRevokedEvent.EVENT_TYPE;
    }

    @Override
    public void handle(String eventId, String payloadJson) {
        ImportBatchRevokedEvent event;
        try {
            event = objectMapper.readValue(payloadJson, ImportBatchRevokedEvent.class);
        } catch (JacksonException e) {
            log.error("[实收撤销] 事件反序列化失败：eventId={}", eventId, e);
            return;
        }

        String sourceType = event.getSourceType();
        if (sourceType == null) {
            return;
        }
        if (!SOURCE_TYPE_KE_RECEIVED.equals(sourceType) && !SOURCE_TYPE_HISTORY_PAYROLL.equals(sourceType)) {
            return;
        }

        Long batchId = event.getBatchId();
        log.info("[实收撤销] 开始清理：batchId={}, sourceType={}", batchId, sourceType);

        // 1. 硬删实收明细
        int details = detailMapper.delete(new LambdaQueryWrapper<ReceivedDetail>()
            .eq(ReceivedDetail::getSourceBatchId, batchId));
        log.info("[实收撤销] 明细已删：batchId={}, count={}", batchId, details);

        // 2. 硬删实收合同
        int contracts = contractMapper.delete(new LambdaQueryWrapper<ReceivedContract>()
            .eq(ReceivedContract::getBatchId, batchId));
        log.info("[实收撤销] 合同已删：batchId={}, count={}", batchId, contracts);

        // 3. 硬删关联实收审批单
        int applies = applyMapper.delete(new LambdaQueryWrapper<ReceivedApply>()
            .eq(ReceivedApply::getBatchId, batchId));
        log.info("[实收撤销] 审批单已删：batchId={}, count={}", batchId, applies);
    }
}
