package com.panjia.ledger.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 部门收支域 MyBatis 配置。
 */
@Configuration
@MapperScan("com.panjia.ledger.mapper")
public class LedgerMybatisConfig {
}
