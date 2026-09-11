package com.panjia.outbox.dispatcher;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.OutboxStatusEnum;
import com.panjia.outbox.entity.OutboxEvent;
import com.panjia.outbox.mapper.OutboxEventMapper;
import com.panjia.outbox.mapper.OutboxIdempotentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Outbox 事件投递 Dispatcher（SnailJob 任务）。
 * <p>
 * 适配说明：任务卡伪代码 {@code implements IJob}，SnailJob 1.10 实际用法为
 * {@code @JobExecutor} 注解 + {@code jobExecute(JobArgs)} 方法返回 {@link ExecuteResult}。
 * <p>
 * 分布式约束：注册为 SnailJob 任务并在控制台开启分布式锁，
 * 保证集群同一时刻仅一个实例投递，防止重复消费（锁由 SnailJob 服务端管理）。
 * <p>
 * 投递时序（关键，不容颠倒）：
 * <ol>
 *   <li>{@link #selectPending} 拉取 PENDING 事件</li>
 *   <li>先调用 {@link #dispatchToTarget}（dispatch 抛异常 → handleFailure 退避）</li>
 *   <li>dispatch 成功后 INSERT 幂等记录（{@code ON CONFLICT DO NOTHING}）+ UPDATE 状态为 PROCESSED</li>
 * </ol>
 * <p>
 * <b>幂等语义</b>：pj_outbox_idempotent 是「成功消费」标记，仅在 dispatch 成功路径上写入。
 * 若 dispatch 失败，<b>不</b>写幂等记录，下一轮重试可再次调用 handler（handler 自身需幂等，
 * 参见 {@link com.panjia.performance.handler.ImportBatchArchivedHandler} 内部
 * PerformanceEngine.buildFromBatch 的去重逻辑）。
 * <p>
 * 指数退避参数（固化）：next_retry_at = now + min(30 * 2^retry_count, 600) 秒；
 * 基础 30s，上限 10min；retry_count &gt;= 10 → 直接 FAILED，不再计算退避。
 */
@Slf4j
@Component
@JobExecutor(name = "outboxDispatcher")
public class OutboxDispatcher {

    /** 单次拉取批量上限 */
    private static final int BATCH_LIMIT = 100;

    /** 重试上限：达此值标记 FAILED */
    private static final int MAX_RETRY = 10;

    /** 退避基础间隔（秒） */
    private static final long BASE_BACKOFF_SECONDS = 30;

    /** 退避上限（秒，10 分钟） */
    private static final long MAX_BACKOFF_SECONDS = 600;

    @Autowired
    private OutboxEventMapper outboxEventMapper;

    @Autowired
    private OutboxIdempotentMapper outboxIdempotentMapper;

    /** 事件处理器映射表：eventType → handler（Spring 自动收集所有 DomainEventHandler Bean） */
    @Autowired(required = false)
    private List<DomainEventHandler> handlers;

    private Map<String, DomainEventHandler> handlerMap;

    /**
     * 初始化 handler 路由表。
     */
    @jakarta.annotation.PostConstruct
    void initHandlerMap() {
        handlerMap = handlers == null ? Map.of()
            : handlers.stream().collect(Collectors.toMap(DomainEventHandler::eventType, h -> h, (a, b) -> a));
        log.info("Outbox event handlers registered: {}", handlerMap.keySet());
    }

    /**
     * SnailJob 任务入口：批量拉取待投递事件并处理。
     * <p>
     * 投递时序（先 dispatch 再 mark consumed，确保失败可重试）：
     * <ol>
     *   <li>dispatchToTarget：先调用 handler，让 handler 抛异常可走 handleFailure 退避</li>
     *   <li>handler 成功 → INSERT 幂等记录（ON CONFLICT 静默跳过）+ UPDATE 状态为 PROCESSED</li>
     * </ol>
     * <p>
     * <b>不再使用「insert idempotent → dispatch → update」旧顺序</b>：
     * 旧实现下若 dispatch 失败，幂等记录已写入，下一轮重试 INSERT 冲突被 catch 跳过，
     * handler 永远不会被再次调用，事件卡死在 PENDING。
     *
     * @param jobArgs 任务参数
     * @return 执行结果
     */
    public ExecuteResult jobExecute(JobArgs jobArgs) {
        LocalDateTime now = LocalDateTime.now();
        // 只拉取 PENDING（进行中）事件，终态 PROCESSED/FAILED 不再扫描
        List<OutboxEvent> pending = outboxEventMapper.selectPending(OutboxStatusEnum.PENDING, now, BATCH_LIMIT);
        int processed = 0;
        int skipped = 0;
        int failed = 0;
        for (OutboxEvent event : pending) {
            try {
                // 1. 投递目标（dispatch 先于幂等写入，保证失败时可重试 handler）
                dispatchToTarget(event);
                // 2. 投递成功后插入幂等记录（ON CONFLICT 处理重入：上次 dispatch 成功但状态未流转）
                //    影响行数 0 表示已存在，正常情况不应发生，仅极端崩溃场景兜底
                boolean firstConsume = markConsumed(event);
                // 3. 流转到 PROCESSED（终态），error_message 置空清除重试期残留
                outboxEventMapper.updateStatus(event.getId(), OutboxStatusEnum.PROCESSED, null, now);
                if (firstConsume) {
                    processed++;
                } else {
                    // 重入场景：handler 已成功投递过一次（崩溃前），本轮 dispatch 实际未跑业务（handler 幂等也无副作用）
                    skipped++;
                }
            } catch (Exception e) {
                handleFailure(event, e);
                failed++;
            }
        }
        log.info("Outbox dispatch: processed={}, skipped={}, failed={}", processed, skipped, failed);
        return ExecuteResult.success();
    }

    /**
     * 幂等记录：原子 INSERT ... ON CONFLICT DO NOTHING（PostgreSQL 9.5+）。
     * <p>
     * 返回值语义：
     * <ul>
     *   <li>true = 本次首次消费（affected = 1）</li>
     *   <li>false = 主键冲突，记录已存在（affected = 0）</li>
     * </ul>
     * <p>
     * 与旧 {@code INSERT + catch DuplicateKeyException} 实现对比：
     * <ul>
     *   <li>无异常抛出，无 DuplicateKeyException 日志噪音</li>
     *   <li>避免 PostgreSQL aborted transaction 状态对事务边界的副作用</li>
     *   <li>单条 SQL 完成 check-and-insert，原子、无 TOCTOU 竞态</li>
     * </ul>
     *
     * @param event 待投递事件
     * @return true 表示本次为首次写入；false 表示记录已存在
     */
    public boolean markConsumed(OutboxEvent event) {
        return outboxIdempotentMapper.insertIgnore(event.getEventId(), LocalDateTime.now()) > 0;
    }

    /**
     * 按 event_type 路由到注册的 DomainEventHandler。
     * <p>
     * 无对应 handler 的事件仅记录日志（如未来域事件尚未实现处理器）。
     *
     * @param event 待投递事件
     */
    private void dispatchToTarget(OutboxEvent event) {
        DomainEventHandler handler = handlerMap.get(event.getEventType());
        if (handler != null) {
            log.debug("Dispatch outbox event: eventId={}, type={}, handler={}",
                event.getEventId(), event.getEventType(), handler.getClass().getName());
            handler.handle(event.getEventId(), event.getPayload());
        } else {
            log.debug("No handler for outbox event: eventId={}, type={}",
                event.getEventId(), event.getEventType());
        }
    }

    /**
     * 处理投递失败：指数退避或达上限标记 FAILED。
     * <p>
     * next_retry_at = now + min(30 * 2^retry_count, 600) 秒；
     * retry_count &gt;= 10 → 直接 FAILED，不再计算退避。
     *
     * @param event      失败事件
     * @param cause      失败原因
     */
    private void handleFailure(OutboxEvent event, Exception cause) {
        LocalDateTime now = LocalDateTime.now();
        String errMsg = cause.getMessage();
        int currentRetry = event.getRetryCount() == null ? 0 : event.getRetryCount();
        if (currentRetry + 1 >= MAX_RETRY) {
            // 达上限，流转到 FAILED（终态），不再重试
            outboxEventMapper.updateStatus(event.getId(), OutboxStatusEnum.FAILED, errMsg, now);
            log.warn("Outbox event marked FAILED (retry>={}): eventId={}", MAX_RETRY, event.getEventId());
        } else {
            // 指数退避：min(30 * 2^retryCount, 600) 秒
            long backoff = Math.min(BASE_BACKOFF_SECONDS * (1L << (currentRetry + 1)), MAX_BACKOFF_SECONDS);
            LocalDateTime nextRetry = now.plusSeconds(backoff);
            outboxEventMapper.incrementRetry(event.getId(), nextRetry, errMsg, now);
            log.warn("Outbox event retry: eventId={}, retry={}, nextRetryAt={}",
                event.getEventId(), currentRetry + 1, nextRetry);
        }
    }
}
