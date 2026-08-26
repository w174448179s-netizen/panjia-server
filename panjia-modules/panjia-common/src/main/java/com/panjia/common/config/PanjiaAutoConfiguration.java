package com.panjia.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

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
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.panjia")
public class PanjiaAutoConfiguration {
}
