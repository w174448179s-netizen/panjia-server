package com.panjia.license.crypto.verify;

import com.panjia.license.config.LicenseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * SSL Pinning 证书指纹管理。
 *
 * 铁律 3：SSL Pinning 强制开启，无任何关闭入口（无配置项/环境变量/JVM参数/端点）。
 * 铁律 17：双指纹硬编码，备指纹不可服务端下发。
 *
 * 本类负责构建只信任指定证书指纹的 SSLContext。
 * 支持主 + 备双指纹过渡（证书轮换四阶段）。
 */
@Slf4j
@Component
public class KeyStore {

    private final LicenseProperties properties;
    private volatile SSLContext sslContext;
    private volatile SSLSocketFactory sslSocketFactory;

    public KeyStore(LicenseProperties properties) {
        this.properties = properties;
    }

    /**
     * 构建 SSLContext，只信任主 + 备指纹。
     * 无关闭入口 —— 这是写死的，不允许任何配置覆盖。
     */
    public synchronized SSLContext buildSslContext() {
        if (sslContext != null) {
            return sslContext;
        }
        try {
            TrustManager[] trustManagers = new TrustManager[]{
                    new PinnedTrustManager(properties.getSslPinning().getPrimaryFingerprint(),
                            properties.getSslPinning().getSecondaryFingerprint())
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustManagers, new java.security.SecureRandom());
            sslContext = ctx;
            log.info("[KeyStore] SSL Pinning 已启用，主指纹={}, 备指纹={}",
                    maskFingerprint(properties.getSslPinning().getPrimaryFingerprint()),
                    maskFingerprint(properties.getSslPinning().getSecondaryFingerprint()));
            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException("SSL Pinning 初始化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 SSLSocketFactory（懒加载，线程安全）。
     * 供 hutool HttpRequest.setSSLSocketFactory() 使用。
     */
    public SSLSocketFactory getSSLSocketFactory() {
        if (sslSocketFactory == null) {
            synchronized (this) {
                if (sslSocketFactory == null) {
                    sslSocketFactory = buildSslContext().getSocketFactory();
                }
            }
        }
        return sslSocketFactory;
    }

    /**
     * 获取当前生效的信任管理器列表。
     * 用于 HttpClient 构建。
     */
    public X509TrustManager[] getTrustManagers() {
        return new X509TrustManager[]{
                new PinnedTrustManager(properties.getSslPinning().getPrimaryFingerprint(),
                        properties.getSslPinning().getSecondaryFingerprint())
        };
    }

    /**
     * 判断指定证书指纹是否命中（主或备）。
     */
    public boolean matchesFingerprint(String certFingerprint) {
        return properties.getSslPinning().getPrimaryFingerprint().equals(certFingerprint)
                || properties.getSslPinning().getSecondaryFingerprint().equals(certFingerprint);
    }

    private String maskFingerprint(String fp) {
        if (fp == null || fp.length() < 8) return "******";
        return fp.substring(0, 4) + "****" + fp.substring(fp.length() - 4);
    }
}
