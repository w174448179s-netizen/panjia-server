package com.panjia.commission;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * 结佣域 Spring Boot 自动配置。
 * <p>
 * 通过 SPI 的 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 触发，确保 {@code com.panjia.commission} 包下的所有 bean（Controller / Service /
 * 事件 Handler / Port 适配器等）被注册到 Spring 上下文（与导入域 ImportAutoConfiguration 同款机制）。
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.panjia.commission")
public class CommissionAutoConfiguration {
}
