package com.panjia.payroll.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 薪酬结算域 MyBatis 配置。
 */
@Configuration
@MapperScan("com.panjia.payroll.mapper")
public class PayrollMybatisConfig {
}
