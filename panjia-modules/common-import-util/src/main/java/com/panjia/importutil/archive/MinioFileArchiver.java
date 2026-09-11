package com.panjia.importutil.archive;

import com.panjia.importutil.config.ImportUtilProperties;
import com.panjia.importutil.exception.ImportUtilException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.oss.client.DefaultOssClientImpl;
import org.dromara.common.oss.client.OssClient;
import org.dromara.common.oss.config.OssClientConfig;
import org.dromara.common.oss.properties.OssProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

/**
 * MinIO / S3 兼容存储归档实现。
 * <p>
 * 路径：{@code {keyPrefix}/{bizDir}/{yyyyMMdd}/{uuid}_{filename}}。
 * <p>
 * 仅在 {@code panjia.import-util.storage-type=minio} 时启用；客户端采用懒加载
 * （双检锁），首次 archive 才建连，避免 Spring 启动期被 MinIO 健康状态阻塞。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "panjia.import-util", name = "storage-type", havingValue = "minio")
public class MinioFileArchiver implements FileArchiver {

    private static final String CLIENT_KEY = "panjia-import-archiver";

    private final ImportUtilProperties properties;

    private volatile OssClient client;

    public MinioFileArchiver(ImportUtilProperties properties) {
        this.properties = properties;
    }

    @Override
    public ArchiveResult archive(byte[] content, String originalFilename, String bizDir) {
        if (content == null || content.length == 0) {
            throw new ImportUtilException("归档失败：文件内容为空");
        }
        OssClient oss = getOrInitClient();
        ImportUtilProperties.Minio cfg = properties.getMinio();
        String day = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String safeName = originalFilename == null ? "unnamed" : originalFilename.replaceAll("[\\\\/]", "_");
        String fileName = UUID.randomUUID().toString().replace("-", "") + "_" + safeName;
        String objectKey = buildObjectKey(cfg.getKeyPrefix(), bizDir, day, fileName);
        try {
            oss.upload(objectKey, content);
        } catch (Exception e) {
            throw new ImportUtilException("MinIO 归档失败: " + objectKey, e);
        }
        String hash = sha256(content);
        log.info("文件已归档至 MinIO: key={} ({} bytes, sha256={})", objectKey, content.length, hash);
        return new ArchiveResult(objectKey, hash, content.length);
    }

    @Override
    public byte[] load(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            throw new ImportUtilException("归档路径为空，无法读取");
        }
        OssClient oss = getOrInitClient();
        String key = storagePath.startsWith("/") ? storagePath.substring(1) : storagePath;
        try {
            byte[] data = oss.download(key, (resp, in) -> {
                // BiFunction 不允许抛 checked 异常，必须自吞 IOException 转 RuntimeException
                try (InputStream stream = in) {
                    return stream.readAllBytes();
                } catch (java.io.IOException ioe) {
                    throw new ImportUtilException("MinIO 读取流失败: key=" + key, ioe);
                }
            });
            if (data == null) {
                throw new ImportUtilException("MinIO 返回空数据: key=" + key);
            }
            return data;
        } catch (Exception e) {
            throw new ImportUtilException("MinIO 读取失败: key=" + key + " — 根因："
                + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * 关闭底层 OSS 客户端（容器销毁时触发）。
     */
    @PreDestroy
    public void close() {
        OssClient c = client;
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                log.warn("MinIO 客户端关闭异常: {}", e.getMessage(), e);
            } finally {
                client = null;
            }
        }
    }

    /**
     * 懒加载获取 OSS 客户端（双重检查锁）。
     * <p>
     * 客户端构造时即做网络握手；放在 archive 首次调用时而非 Spring 启动期，
     * 让导入流程对 MinIO 健康状态的依赖向后推迟到真正写归档那一刻。
     */
    private OssClient getOrInitClient() {
        OssClient c = client;
        if (c != null) {
            return c;
        }
        synchronized (this) {
            if (client == null) {
                client = createClient();
            }
            return client;
        }
    }

    private OssClient createClient() {
        ImportUtilProperties.Minio cfg = properties.getMinio();
        if (cfg == null || isBlank(cfg.getEndpoint()) || isBlank(cfg.getAccessKey())
            || isBlank(cfg.getSecretKey()) || isBlank(cfg.getBucketName())) {
            throw new ImportUtilException(
                "storage-type=minio 但 panjia.import-util.minio 未完整配置"
                    + "（endpoint/accessKey/secretKey/bucketName 必填）");
        }
        OssProperties props = new OssProperties();
        props.setEndpoint(cfg.getEndpoint());
        props.setAccessKey(cfg.getAccessKey());
        props.setSecretKey(cfg.getSecretKey());
        props.setBucketName(cfg.getBucketName());
        props.setRegion(cfg.getRegion());
        props.setIsHttps(cfg.getIsHttps());
        props.setAccessPolicy(cfg.getAccessPolicy());
        OssClientConfig ossCfg = OssClientConfig.formProperties(props);
        return new DefaultOssClientImpl(CLIENT_KEY, ossCfg);
    }

    private static String buildObjectKey(String keyPrefix, String bizDir, String day, String fileName) {
        StringBuilder sb = new StringBuilder();
        if (keyPrefix != null && !keyPrefix.isBlank()) {
            sb.append(stripSlash(keyPrefix)).append('/');
        }
        sb.append(stripSlash(bizDir)).append('/').append(day).append('/').append(fileName);
        return sb.toString();
    }

    private static String stripSlash(String s) {
        if (s == null) {
            return "";
        }
        String t = s;
        while (t.startsWith("/")) {
            t = t.substring(1);
        }
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception e) {
            throw new ImportUtilException("摘要计算失败", e);
        }
    }
}