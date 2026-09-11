package com.panjia.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 盘家智管自动配置
 * <p>
 * 通过 Spring Boot 自动装配机制注册 com.panjia 包下的所有组件，
 * 无需修改底座 DromaraApplication 的组件扫描路径。
 * <ul>
 *   <li>幂等切面 IdempotentAspect</li>
 *   <li>审计日志切面 AuditLogAspect</li>
 *   <li>全局异常处理 GlobalExceptionHandler</li>
 *   <li>License 拦截器链</li>
 * </ul>
 *
 * <p>{@link EnableScheduling}：启用 Spring 内置调度器（{@code @Scheduled}），
 * 用于本地开发环境 SnailJob 禁用时的 OutboxDispatcher 轮询 fallback（见
 * {@code com.panjia.outbox.dispatcher.OutboxDevFallbackScheduler}）。
 * 生产环境 {@code snail-job.enabled=true} 时，SnailJob 仍是主调度器。
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.panjia")
@EnableScheduling
public class PanjiaAutoConfiguration {
}
