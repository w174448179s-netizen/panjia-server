package com.panjia.importutil;

import com.panjia.importutil.config.ImportUtilProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/**
 * common-import-util 自动装配。
 * <p>
 * 工具层入口：扫描 {@code com.panjia.importutil} 下的 parser / archive / validate 组件。
 * 不含任何业务库操作（无 DataSource / Mapper / Repository）。
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.panjia.importutil")
@EnableConfigurationProperties(ImportUtilProperties.class)
public class ImportUtilAutoConfiguration {
}
