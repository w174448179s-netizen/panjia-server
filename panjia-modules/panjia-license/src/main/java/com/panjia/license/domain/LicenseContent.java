package com.panjia.license.domain;

import io.jsonwebtoken.Claims;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Date;

/**
 * License 内容实体。
 * JWT token 的 payload 载体，含指纹哈希、版本范围、过期时间等。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LicenseContent {

    /** 授权码 */
    private String authCode;

    /** 机器指纹哈希（hostMachineId + instanceId 派生） */
    private String fingerprintHash;

    /** 宿主机机器 ID（兼容旧版 payload 格式） */
    private String hostMachineId;

    /** 版本范围下限 */
    private String minVersion;

    /** 版本范围上限 */
    private String maxVersion;

    /** Token 签发时间 */
    private Instant issuedAt;

    /** Token 过期时间 */
    private Instant expiresAt;

    /** 离线宽限期截止（心跳刷新值） */
    private Instant offlineExpireAt;

    /** 客户端模式指令 */
    private String clientMode;

    /** 授权有效期截止（到期保护期） */
    private Instant licenseExpireAt;

    /**
     * 从 JWT Claims 构建 LicenseContent。
     * 兼容多种 payload 字段命名约定。
     * @param claims JWT payload
     * @return LicenseContent
     */
    public static LicenseContent fromClaims(Claims claims) {
        LicenseContentBuilder builder = builder()
                .authCode(claims.get("authCode", String.class))
                .fingerprintHash(claims.get("fingerprintHash", String.class))
                .hostMachineId(claims.get("hostMachineId", String.class))
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

    /**
     * 获取过期时间戳（毫秒）。
     * 兼容基于 long 的时间比较代码。
     * @return 过期时间毫秒数，未设置返回 0
     */
    public long getExpireAt() {
        return expiresAt != null ? expiresAt.toEpochMilli() : 0L;
    }

    private static Instant toInstant(Date date) {
        return date != null ? date.toInstant() : null;
    }
}
