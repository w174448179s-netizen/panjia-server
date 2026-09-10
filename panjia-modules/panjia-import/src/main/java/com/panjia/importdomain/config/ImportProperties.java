package com.panjia.importdomain.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 导入域配置项。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "panjia.import")
public class ImportProperties {

    /** 批量插入大小 */
    private int batchInsertSize = 300;

    /** 单批次最大行数 */
    private int maxRowsPerBatch = 5000;
}
