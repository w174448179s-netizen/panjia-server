package com.panjia.dingtalk.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 钉钉域 Mapper 扫描。
 * <p>
 * 底座 MybatisPlusConfig 的 @MapperScan 只扫描 {@code org.dromara.**.mapper}，
 * 且显式 @MapperScan 存在时 mybatis-spring-boot 的 @Mapper 自动扫描会退让，
 * 因此各业务模块需自建扫描配置。
 */
@Configuration
@MapperScan("com.panjia.dingtalk.mapper")
public class DingTalkMybatisConfig {
}
