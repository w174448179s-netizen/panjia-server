package com.panjia.outbox.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * panjia-outbox MyBatis Mapper 扫描配置。
 * <p>
 * 底座 MybatisPlusConfig 的 @MapperScan 只扫描 {@code org.dromara.**.mapper}，
 * 且显式 @MapperScan 存在时 mybatis-spring-boot 的 @Mapper 自动扫描会退让，
 * 因此本模块自建扫描配置，覆盖 {@code com.panjia.outbox.mapper} 包下的 Mapper。
 */
@Configuration
@MapperScan("com.panjia.outbox.mapper")
public class OutboxMybatisConfig {
}
