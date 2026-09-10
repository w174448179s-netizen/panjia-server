package com.panjia.importdomain.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 导入域 MyBatis 配置。
 */
@Configuration
@MapperScan("com.panjia.importdomain.mapper")
public class ImportMybatisConfig {
}
