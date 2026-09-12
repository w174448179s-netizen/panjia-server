package com.panjia.commission.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 结佣域 MyBatis 配置。
 */
@Configuration
@MapperScan("com.panjia.commission.mapper")
public class CommissionMybatisConfig {
}
