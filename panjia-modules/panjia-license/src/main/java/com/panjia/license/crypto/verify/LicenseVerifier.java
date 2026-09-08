package com.panjia.license.crypto.verify;

import com.panjia.license.config.LicenseProperties;
import com.panjia.license.domain.LicenseContent;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * License JWT 签名验签核心（RSA 非对称）。
 *
 * 安全模型：
 * - 授权服务器持有私钥，签发 token。
 * - 客户端 JAR 内嵌公钥（license-public-key.pem），只能验签，不能签名。
 * - 客户无法伪造合法 token，因为没有私钥。
 *
 * 公钥来源：classpath 资源 license-public-key.pem（JAR 内，不可被外部修改）。
 */
@Slf4j
@Component
public class LicenseVerifier {

    /** 时钟偏差兜底默认值（秒）。防止 LicenseProperties 未注入时 JWTS 把 token 当场判过期 */
    private static final int DEFAULT_CLOCK_SKEW_SECONDS = 60;

    private final PublicKey publicKey;
    private final int clockSkewSeconds;

    public LicenseVerifier(LicenseProperties properties) {
        this.publicKey = loadPublicKey();
        this.clockSkewSeconds = properties != null && properties.getClockSkewSeconds() > 0
                ? properties.getClockSkewSeconds()
                : DEFAULT_CLOCK_SKEW_SECONDS;
    }

    /**
     * 从 classpath 加载内嵌的 RSA 公钥证书。
     */
    private static PublicKey loadPublicKey() {
        try (InputStream is = LicenseVerifier.class.getClassLoader()
                .getResourceAsStream("license-public-key.pem")) {
            if (is == null) {
                throw new IllegalStateException("license-public-key.pem 未找到（JAR 内嵌资源缺失）");
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(is);
            log.info("[LicenseVerifier] RSA 公钥加载成功: {}", cert.getSubjectX500Principal().getName());
            return cert.getPublicKey();
        } catch (Exception e) {
            throw new IllegalStateException("License 公钥加载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解码并验证 token 签名（RSA 验签）。
     *
     * ★ P2-3 显式锁 RS256：jjwt 0.12 在 verifyWith(publicKey) 时会按公钥类型拒绝
     *   alg confusion 攻击（RSA 公钥 + alg=HS256 的 token 会被拒绝），但这是隐式
     *   行为。纵深防御：parseSignedClaims 后显式校验 header.alg == RS256，等于
     *   在 jjwt 自身防御之上再加一道显式断言，未来如果 jjwt 升级或换库不会留缺口。
     *
     * @throws JwtException 签名无效/过期/算法不符时抛出
     */
    public LicenseContent decodeToken(String token) {
        try {
            Jws<Claims> claimsJws = Jwts.parser()
                    .verifyWith(publicKey)
                    .clock(() -> new Date(System.currentTimeMillis()))
                    .clockSkewSeconds(clockSkewSeconds)
                    .build()
                    .parseSignedClaims(token);

            // ★ P2-3 显式算法断言：防御 alg confusion 攻击
            String alg = claimsJws.getHeader().getAlgorithm();
            if (!"RS256".equalsIgnoreCase(alg)) {
                log.warn("[LicenseVerifier] 拒绝非 RS256 算法 token: alg={}", alg);
                throw new io.jsonwebtoken.security.SecurityException(
                        "Unsupported JWT algorithm: " + alg + " (expected RS256)");
            }

            Claims claims = claimsJws.getPayload();
            // P1-E 修复：统一走 LicenseContent.fromClaims（P2-2 完整实现，19 个字段全量填充）。
            // 原先这里的私有 toLicenseContent 只填 8 个字段，导致运行时
            // customerNo/plan/capabilities/maxStores/endDate/keyVersion 等业务字段恒为 null，
            // 业务侧按能力位/配额做操作粒度控制全部落空。
            return LicenseContent.fromClaims(claims);
        } catch (JwtException e) {
            log.warn("[LicenseVerifier] token 验证失败: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * 验证 token 未过期。
     */
    public boolean isTokenValid(String token) {
        try {
            decodeToken(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }
}
