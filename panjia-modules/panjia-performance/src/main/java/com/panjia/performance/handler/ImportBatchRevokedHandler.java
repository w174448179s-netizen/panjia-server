package com.panjia.performance.handler;

import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.ImportBatchRevokedEvent;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.ReceivedApply;
import com.panjia.performance.mapper.PerformanceConsumeLogMapper;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.mapper.ReceivedApplyMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.workflow.api.WorkflowService;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 导入批次撤销事件处理器（DomainEventHandler）。
 * <p>
 * 收到 ImportBatchRevokedEvent 后，硬删业绩域所有关联数据：
 * <ul>
 *   <li>业绩事实（pj_perf_fact）</li>
 *   <li>实收审批单（pj_perf_received_apply）及其关联的流程实例（走 WorkflowService 标准链路）</li>
 *   <li>消费日志（pj_perf_consume_log）</li>
 * </ul>
 * <p>
 * 幂等：重复消费不会报错（删了再删也没事）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportBatchRevokedHandler implements DomainEventHandler {

    private final PerformanceFactMapper factMapper;
    private final ReceivedApplyMapper receivedApplyMapper;
    private final PerformanceConsumeLogMapper consumeLogMapper;
    private final WorkflowService workflowService;
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
            log.error("[批次撤销-业绩域] 事件反序列化失败：eventId={}", eventId, e);
            return;
        }
        Long batchId = event.getBatchId();

        log.info("[批次撤销-业绩域] 开始处理：batchId={}, sourceType={}, period={}",
            batchId, event.getSourceType(), event.getPeriod());

        // ① 硬删该批次的所有业绩事实
        int factCount = factMapper.delete(new LambdaQueryWrapper<PerformanceFact>()
            .eq(PerformanceFact::getBatchId, batchId));
        log.info("[批次撤销-业绩域] 业绩事实已删除：batchId={}, count={}", batchId, factCount);

        // ② 硬删实收审批单及其关联的流程实例（系统级删除：事件处理器无登录上下文，不走权限校验与业务删除事件）
        List<ReceivedApply> applies = receivedApplyMapper.selectList(
            new LambdaQueryWrapper<ReceivedApply>().eq(ReceivedApply::getBatchId, batchId));
        if (!applies.isEmpty()) {
            List<String> businessIds = applies.stream()
                .map(a -> String.valueOf(a.getId())).toList();
            // 删流程实例（含任务/历史/实例/业务扩展，业务数据由本处理器自行清理，故走系统级链路）
            boolean wfDeleted = workflowService.deleteInstanceSys(businessIds);
            log.info("[批次撤销-业绩域] 关联流程实例删除：batchId={}, applyCount={}, result={}",
                batchId, applies.size(), wfDeleted);
            // 删实收审批单
            receivedApplyMapper.delete(
                new LambdaQueryWrapper<ReceivedApply>().eq(ReceivedApply::getBatchId, batchId));
            log.info("[批次撤销-业绩域] 实收审批单已删除：batchId={}, count={}",
                batchId, applies.size());
        }

        // ③ 硬删消费日志
        int logCount = consumeLogMapper.delete(new LambdaQueryWrapper<com.panjia.performance.domain.PerformanceConsumeLog>()
            .eq(com.panjia.performance.domain.PerformanceConsumeLog::getBatchId, batchId));
        log.info("[批次撤销-业绩域] 消费日志已删除：batchId={}, count={}", batchId, logCount);

        log.info("[批次撤销-业绩域] 完成：batchId={}, 删除事实={}, 删除实收单={}, 删除消费日志={}",
            batchId, factCount, applies.size(), logCount);
    }
}
