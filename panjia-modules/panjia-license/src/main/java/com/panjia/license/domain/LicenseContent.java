package com.panjia.license.domain;

import io.jsonwebtoken.Claims;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * License 内容实体（不可变）。
 * JWT token 的 payload 载体，含指纹哈希、版本范围、过期时间等。
 *
 * 安全设计：此类不可变，无 setter。外部拿到引用后无法篡改任何字段。
 * 需要更新 offlineExpireAt 时，通过 LicenseContext.updateOfflineExpireAt()
 * 内部用 toBuilder() 生成新对象替换引用，外部无感知。
 *
 * ★ P2-2 业务字段扩展：原版只有 auth/fingerprint/version/expire，业务侧拿不到
 *   客户编号/版本套餐/能力列表/最大门店数/最大用户数，无法做"按操作粒度控制"
 *   （V1.3 §2.5 三类 operation + capabilities 校验）。本次扩展后 JwtIssuer 已签
 *   发的全部业务字段都能在 LicenseContent 上直接读到。
 */
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class LicenseContent {

    /** 授权码 */
    private String authCode;

    /** 客户编号（用于业务侧按客户维度记录日志/审计/工单） */
    private String customerNo;

    /** 公司名称（业务展示用） */
    private String company;

    /** 版本套餐/版本号（业务侧做版本号 display & 灰度控制） */
    private String plan;

    /** 机器指纹哈希（hostMachineId + instanceId 派生） */
    private String fingerprintHash;

    /** 宿主机机器 ID（兼容旧版 payload 格式） */
    private String hostMachineId;

    /** 允许的最大门店数（业务侧做 capacity 校验） */
    private Integer maxStores;

    /** 允许的最大用户数（业务侧做 capacity 校验） */
    private Integer maxUsers;

    /** 能力清单（JSON 数组反序列化后的字符串列表，如 ["salary","attendance"]） */
    private List<String> capabilities;

    /** 授权起始日期（业务展示用） */
    private LocalDate startDate;

    /** 授权结束日期（业务展示用） */
    private LocalDate endDate;

    /** 维保结束日期（业务侧做"维保期内/外"提示） */
    private LocalDate maintenanceEndDate;

    /** 版本范围下限 */
    private String minVersion;

    /** 版本范围上限 */
    private String maxVersion;

    /** 当前 key 版本号（用于多套密钥轮换时灰度切换） */
    private Integer keyVersion;

    /** License 业务版本号（同 authCode 内容变更时 +1） */
    private Integer licenseVersion;

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
        return builder()
                .authCode(claims.get("authCode", String.class))
                .customerNo(claims.get("customerNo", String.class))
                .company(claims.get("company", String.class))
                .plan(claims.get("plan", String.class))
                .fingerprintHash(claims.get("fingerprintHash", String.class))
                .hostMachineId(claims.get("hostMachineId", String.class))
                .maxStores(claims.get("maxStores", Integer.class))
                .maxUsers(claims.get("maxUsers", Integer.class))
                .capabilities(parseCapabilities(claims.get("capabilities", List.class)))
                .startDate(parseLocalDate(claims.get("startDate", String.class)))
                .endDate(parseLocalDate(claims.get("endDate", String.class)))
                .maintenanceEndDate(parseLocalDate(claims.get("maintenanceEndDate", String.class)))
                .minVersion(claims.get("minVersion", String.class))
                .maxVersion(claims.get("maxVersion", String.class))
                .keyVersion(claims.get("keyVersion", Integer.class))
                .licenseVersion(claims.get("licenseVersion", Integer.class))
                .issuedAt(toInstant(claims.getIssuedAt()))
                .expiresAt(toInstant(claims.getExpiration()))
                .offlineExpireAt(toInstant(claims.get("offlineExpireAt", Date.class)))
                .clientMode(claims.get("clientMode", String.class))
                .licenseExpireAt(toInstant(claims.get("licenseExpireAt", Date.class)))
                .build();
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

    private static LocalDate parseLocalDate(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 capabilities claim 为不可变 List<String>。
     * 容错：null/类型不符都返回空列表，避免后续 NPE。
     * 拷贝为不可变 List 防止外部通过引用篡改 LicenseContent 的能力清单。
     * 借助 jjwt-jackson（jjwt-jackson 已传递依赖 jackson-databind 作为底层序列化器），
     * 无需为 LicenseContent 单独引入 ObjectMapper 依赖。
     */
    @SuppressWarnings("unchecked")
    private static List<String> parseCapabilities(Object raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        if (raw instanceof List) {
            List<?> src = (List<?>) raw;
            List<String> result = new ArrayList<>(src.size());
            for (Object e : src) {
                if (e instanceof String) {
                    result.add((String) e);
                }
            }
            return Collections.unmodifiableList(result);
        }
        return Collections.emptyList();
    }
}
