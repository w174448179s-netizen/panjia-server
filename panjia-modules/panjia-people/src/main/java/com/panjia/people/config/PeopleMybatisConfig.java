package com.panjia.people.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * panjia-people MyBatis 扫描配置。
 * <p>
 * 底座默认 mapperPackage 为 {@code org.dromara.**.mapper}，不覆盖 com.panjia 包，
 * 故显式扫描 people 域 Mapper 所在包（infrastructure.repository）。
 */
@Configuration
@MapperScan("com.panjia.people.infrastructure.repository")
public class PeopleMybatisConfig {
}
