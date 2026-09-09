package com.panjia.people.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * panjia-people MyBatis Mapper 扫描配置。
 * <p>
 * 底座 MybatisPlusConfig 的 @MapperScan 只扫描 {@code org.dromara.**.mapper}，
 * 因此本模块自建扫描配置，覆盖 {@code com.panjia.people.mapper} 包下的 Mapper。
 */
@Configuration
@MapperScan("com.panjia.people.mapper")
public class PeopleMybatisConfig {
}
