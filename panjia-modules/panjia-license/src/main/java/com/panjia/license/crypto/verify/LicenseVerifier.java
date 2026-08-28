package com.panjia.license.crypto.verify;

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
import java.time.Instant;
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

    private final PublicKey publicKey;

    public LicenseVerifier() {
        this.publicKey = loadPublicKey();
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
     * @throws JwtException 签名无效/过期时抛出
     */
    public LicenseContent decodeToken(String token) {
        try {
            Jws<Claims> claimsJws = Jwts.parser()
                    .verifyWith(publicKey)
                    .clock(() -> new Date(System.currentTimeMillis()))
                    .build()
                    .parseSignedClaims(token);
            Claims claims = claimsJws.getPayload();
            return toLicenseContent(claims);
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

    private LicenseContent toLicenseContent(Claims claims) {
        LicenseContent.LicenseContentBuilder builder = LicenseContent.builder()
                .authCode(claims.get("authCode", String.class))
                .fingerprintHash(claims.get("fingerprintHash", String.class))
                .minVersion(claims.get("minVersion", String.class))
                .maxVersion(claims.get("maxVersion", String.class))
                .issuedAt(toInstant(claims.getIssuedAt()))
                .expiresAt(toInstant(claims.getExpiration()))
                .offlineExpireAt(toInstant(claims.get("offlineExpireAt", Date.class)))
                .clientMode(claims.get("clientMode", String.class));

        if (claims.get("licenseExpireAt", Date.class) != null) {
            builder.licenseExpireAt(toInstant(claims.get("licenseExpireAt", Date.class)));
        }
        return builder.build();
    }

    private Instant toInstant(Date date) {
        return date != null ? date.toInstant() : null;
    }
}
