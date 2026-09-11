package com.panjia.outbox.dispatcher;

import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 本地开发环境 Outbox Dispatcher Fallback 调度器。
 * <p>
 * <b>触发条件</b>：仅当 {@code snail-job.enabled=false}（dev 默认值）时激活，
 * 通过 Spring 内置 {@link Scheduled} 定时轮询调用
 * {@link OutboxDispatcher#jobExecute(JobArgs)}，绕过独立部署的 SnailJob Server。
 * <p>
 * <b>生产约束</b>：生产环境必须保持 {@code snail-job.enabled=true}，
 * 由 SnailJob 服务端调度 + 分布式锁保障集群不重复消费；
 * 本类在生产配置下不会被注册（{@code @ConditionalOnProperty} 守卫）。
 * <p>
 * <b>为何不直接用 {@code @Scheduled} 替代 {@code @JobExecutor}</b>：
 * 分布式部署时多实例会同时跑同一段逻辑，缺少 SnailJob 的分布式锁会重复触发 handler。
 * 本类只在单机 dev 环境兜底，集群仍走 SnailJob。
 *
 * @since V2.0 §4.1
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "snail-job.enabled", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxDevFallbackScheduler {

    private final OutboxDispatcher outboxDispatcher;

    /**
     * 每 5 秒轮询一次，与 SnailJob 默认间隔一致。
     * <p>
     * 首次延迟 5s 避免应用启动时与 Flyway / Bean 装配抢资源。
     */
    @Scheduled(fixedDelay = 5000L, initialDelay = 5000L)
    public void pollOutbox() {
        try {
            outboxDispatcher.jobExecute(new JobArgs());
        } catch (Exception e) {
            // 不抛出阻断调度（OutboxDispatcher 内部已做 try/catch 转 FAILED，这里再兜一层防线程挂掉）
            log.warn("[OutboxDevFallback] 轮询异常：{}", e.getMessage(), e);
        }
    }
}