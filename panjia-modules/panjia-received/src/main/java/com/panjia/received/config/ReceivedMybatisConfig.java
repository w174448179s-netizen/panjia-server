package com.panjia.received.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 实收域 MyBatis-Plus Mapper 扫描配置。
 * <p>
 * 底座 MybatisPlusConfig 的 @MapperScan 只扫描 org.dromara.**.mapper，
 * 实收域 Mapper（com.panjia.received.mapper）需本模块显式扫描。
 */
@Configuration
@MapperScan("com.panjia.received.mapper")
public class ReceivedMybatisConfig {
}
