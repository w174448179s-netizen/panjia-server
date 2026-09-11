package com.panjia.importdomain;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * 导入域 Spring Boot 自动配置。
 * <p>
 * 通过 SPI 的 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 触发，确保 {@code com.panjia.importdomain} 包下的所有 bean（包括 Controller、Service、
 * {@code ImportDomainExceptionHandler} 等）被注册到 Spring 上下文。
 * <p>
 * <b>历史背景</b>：之前模块下的组件依赖 {@code panjia-common.PanjiaAutoConfiguration} 的
 * {@code @ComponentScan(basePackages = "com.panjia")} 隐式扫描，但实际运行时该扫描未覆盖到
 * {@code @RestControllerAdvice}（被 {@code GlobalLicenseExceptionHandler} 的兜底抢先匹配）。
 * 本 AutoConfiguration 显式扫描本域，{@code ImportDomainExceptionHandler} 配合
 * {@code @Order(Ordered.HIGHEST_PRECEDENCE)} 强制最高优先级，业务异常不再穿透到全局兜底。
 *
 * @see com.panjia.importdomain.controller.ImportDomainExceptionHandler
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.panjia.importdomain")
public class ImportAutoConfiguration {
}