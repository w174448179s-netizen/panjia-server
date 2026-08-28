package com.panjia.license.crypto.verify;

import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * 证书指纹匹配 TrustManager。
 * 仅当证书指纹命中主或备指纹时信任，否则 SSL 握手失败。
 *
 * 铁律 3：无任何关闭入口。本类不读任何配置，指纹硬编码在调用方。
 */
@Slf4j
class PinnedTrustManager implements X509TrustManager {

    private final List<String> allowedFingerprints;

    PinnedTrustManager(String primary, String secondary) {
        this.allowedFingerprints = List.of(primary, secondary);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {
        // 客户端认证非本场景所需，直接放行（授权服务器为服务端，无需客户端证书）
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) {
        if (chain == null || chain.length == 0) {
            throw new IllegalArgumentException("空的证书链");
        }
        X509Certificate cert = chain[0];
        String fingerprint = calculateFingerprint(cert);
        log.debug("[PinnedTrustManager] 证书指纹校验: {} -> {}", fingerprint, allowedFingerprints);
        if (!allowedFingerprints.contains(fingerprint)) {
            throw new RuntimeException("SSL Pinning 失败：证书指纹未命中，拒绝握手。可能是 SSL 中间人代理拦截。");
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }

    /**
     * 计算证书 SHA-256 指纹（十六进制）。
     * 与配置中的主/备指纹比对。
     */
    private String calculateFingerprint(X509Certificate cert) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("计算证书指纹失败", e);
        }
    }
}
