package com.panjia.license.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * License 模块配置属性。
 * 对应 application.yml 中 panjia.license.* 节点。
 * 通过 @EnableConfigurationProperties 在 LicenseAutoConfiguration 中注册，不需要 @Component。
 */
@Data
@ConfigurationProperties(prefix = "panjia.license")
public class LicenseProperties {

    /** 是否启用 License 校验 */
    private boolean enabled = true;

    /** 授权服务器基础地址 */
    private String serverUrl = "https://license.panjia.com";

    /** JWT 签名密钥（Base64 编码） */
    private String signingKey = "cGFuamlhLWxpY2Vuc2Utc2lnbmluZy1rZXktMjAyNg==";

    /** 心跳间隔（毫秒），默认 24h */
    private long heartbeatIntervalMs = 86_400_000L;

    /** /check 结果缓存有效期（毫秒），默认 30 分钟 */
    private long checkCacheTtlMs = 1_800_000L;

    /** 离线宽限期（毫秒），默认 7 天 */
    private long offlineGraceMs = 604_800_000L;

    /** 单调时钟 TCP 连接超时（毫秒） */
    private int tcpTimeoutMs = 5000;

    /** 持久化数据卷根目录 */
    private String dataDir = "/data";

    /** 状态文件名 */
    private FileNames file = new FileNames();

    /** SSL Pinning 双指纹 */
    private SslPinning sslPinning = new SslPinning();

    /** 日志配置 */
    private Log log = new Log();

    @Data
    public static class FileNames {
        private String instanceId = ".panjia_instance_id";
        private String license = "license.lic";
        private String token = ".panjia_token";
        private String monotonic = ".panjia_monotonic";
        private String checksums = "panjia-checksums";
    }

    @Data
    public static class SslPinning {
        /** 主指纹（当前生效） */
        private String primaryFingerprint = "AbC123dEf456GhI789jKlM012nOp345qRs";
        /** 备指纹（过渡期可先信任） */
        private String secondaryFingerprint = "xYz901aBc234dEf567gHh890iJk123lMn456oP";
    }

    @Data
    public static class Log {
        /** 受限模式告警日志路径 */
        private String restrictedAlertFile = "/data/panjia_restricted_alert.log";
    }
}
