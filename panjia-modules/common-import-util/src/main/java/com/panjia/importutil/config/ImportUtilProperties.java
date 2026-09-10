package com.panjia.importutil.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 导入工具层配置项。
 */
@Data
@ConfigurationProperties(prefix = "panjia.import-util")
public class ImportUtilProperties {

    /** 归档存储类型：local / minio */
    private String storageType = "local";

    /** 单文件最大数据行数（防大事务/OOM） */
    private int maxRows = 5000;

    /** 本地归档根目录（storage-type=local 时生效） */
    private String localBaseDir = "/tmp/panjia-import";
}
