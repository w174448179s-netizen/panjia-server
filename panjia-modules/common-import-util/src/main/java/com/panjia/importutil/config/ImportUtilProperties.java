package com.panjia.importutil.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * 导入工具层配置项。
 */
@Data
@ConfigurationProperties(prefix = "panjia.import-util")
public class ImportUtilProperties {

    /** 归档存储类型：local / minio（默认 local 兜底，minio 需在 panjia.import-util.minio 下补配置） */
    private String storageType = "local";

    /** 单文件最大数据行数（防大事务/OOM） */
    private int maxRows = 5000;

    /** 本地归档根目录（storage-type=local 时生效） */
    private String localBaseDir = "/tmp/panjia-import";

    /** MinIO 配置（storage-type=minio 时生效） */
    @NestedConfigurationProperty
    private Minio minio = new Minio();

    /**
     * MinIO / S3 兼容存储配置。
     * <p>
     * MinIO 默认走路径风格访问 + http，配置简单。
     */
    @Data
    public static class Minio {

        /** MinIO 访问站点（例：http://127.0.0.1:9000） */
        private String endpoint;

        /** ACCESS_KEY */
        private String accessKey;

        /** SECRET_KEY */
        private String secretKey;

        /** 存储桶 */
        private String bucketName;

        /** 区域（MinIO 可任意填，默认 us-east-1） */
        private String region = "us-east-1";

        /** 是否 HTTPS（Y/N，MinIO 本地部署默认 N） */
        private String isHttps = "N";

        /** 桶 ACL（0private 1public 2custom） */
        private String accessPolicy = "1";

        /** 业务对象键前缀（最终路径：{keyPrefix}/{bizDir}/{yyyyMMdd}/{uuid}_{filename}） */
        private String keyPrefix = "panjia-import";
    }
}
