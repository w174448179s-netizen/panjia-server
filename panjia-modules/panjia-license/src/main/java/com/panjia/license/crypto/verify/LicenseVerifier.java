package com.panjia.license.crypto.verify;

import com.panjia.license.config.LicenseProperties;
import com.panjia.license.domain.LicenseContent;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * License JWT 签名验签核心。
 * 负责 token 的解码、签名验证、payload 提取。
 */
@Slf4j
@Component
public class LicenseVerifier {

    private final SecretKey signingKey;

    public LicenseVerifier(LicenseProperties properties) {
        this.signingKey = deriveKey(properties.getSigningKey());
    }

    /**
     * 从配置的密钥字符串派生 32 字节 HMAC-SHA256 密钥。
     * 兼容 Base64 编码和原始字符串，无论长度多少都能生成合法密钥。
     */
    private static SecretKey deriveKey(String keyStr) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(keyStr);
        } catch (IllegalArgumentException e) {
            raw = keyStr.getBytes(StandardCharsets.UTF_8);
        }
        // SHA-256 派生，确保 32 字节（HS256 最低要求）
        try {
            byte[] derived = MessageDigest.getInstance("SHA-256").digest(raw);
            return Keys.hmacShaKeyFor(derived);
        } catch (Exception e) {
            throw new IllegalStateException("License 签名密钥派生失败", e);
        }
    }

    /**
     * 解码并验证 token 签名。
     * @throws JwtException 签名无效/过期时抛出
     */
    public LicenseContent decodeToken(String token) {
        try {
            Jws<Claims> claimsJws = Jwts.parser()
                    .verifyWith(signingKey)
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
