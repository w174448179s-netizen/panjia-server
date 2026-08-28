package com.panjia.license;

import com.panjia.license.config.LicenseProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/**
 * License 模块 Spring Boot 自动配置。
 * 通过 SPI 的 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 触发。
 *
 * 模块内组件通过 @ComponentScan 自动发现（@Service、@Component、@RestController 等）。
 */
@AutoConfiguration
@EnableConfigurationProperties(LicenseProperties.class)
@ComponentScan(basePackages = "com.panjia.license")
public class LicenseAutoConfiguration {

}
