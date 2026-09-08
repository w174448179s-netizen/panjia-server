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

    /** 授权服务器基础地址 */
    private String serverUrl = "https://panjia.icu";

    /** 授权码（首次部署时由管理后台生成，激活后作废） */
    private String authCode = "";

    /** 产品版本号（激活时上报） */
    private String productVersion = "1.0.0";

    /** 心跳间隔（毫秒），默认 24h */
    private long heartbeatIntervalMs = 86_400_000L;

    /** token 续签阈值（毫秒），默认 30 天。
     *  心跳时若 token 剩余有效期小于此值，服务端可返回新 token 自动续签。 */
    private long tokenRenewThresholdMs = 2_592_000_000L;

    /** /check 结果缓存有效期（毫秒），默认 30 分钟 */
    private long checkCacheTtlMs = 1_800_000L;

    /** 离线宽限期（毫秒），默认 7 天；超过后未成功心跳则进入离线锁死 */
    private long offlineGraceMs = 604_800_000L;

    /** 心跳连续失败次数阈值，达到后进入离线宽限期（OFFLINE_GRACE），默认 3 次 */
    private int heartbeatFailureGraceThreshold = 3;

    /** check 失败后服务器不可用短路窗口（毫秒），默认 30 分钟。
     *  在此期间 check 不再发 HTTP 请求，直接走缓存/拒绝，避免每次操作等 TCP 超时。
     *  窗口过后下次 check 会再试一次探测服务器是否恢复。
     *  按 V1.3 §2.5 设计，应与 checkCacheTtlMs 对齐，避免缓存外的请求穿透造成雪崩。 */
    private long checkFailureBackoffMs = 1_800_000L;

    /** JWT 时钟偏差容忍（秒），默认 60 秒。
     *  双端需保持一致配置，避免客户端/服务端时钟漂移导致 token 被误判过期。 */
    private int clockSkewSeconds = 60;

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
        private String token = ".panjia_token";
        private String monotonic = ".panjia_monotonic";
        private String checksums = "panjia-checksums";
    }

    @Data
    public static class SslPinning {
        /** 主指纹（当前生效，panjia.icu 证书 SHA-256 小写无冒号） */
        private String primaryFingerprint = "98c173cbc20f87ea6667c41a32e0e48365f80e1213e7d9dcc4044a7b2746e6da";
        /** 备指纹（证书轮换过渡期先信任，留空表示无备指纹；续签时取新证书指纹填入此处，切换后再清空旧主） */
        private String secondaryFingerprint = "";
    }

    @Data
    public static class Log {
        /** 受限模式告警日志路径 */
        private String restrictedAlertFile = "/data/panjia_restricted_alert.log";
    }
}
