package com.panjia.license.security;

import com.panjia.license.config.LicenseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * License 强制守卫。
 *
 * 安全策略（安全默认 / 白名单关闭）：
 * - 只有 dev / local 两个开发 profile 才允许通过 enabled=false 关闭 License。
 * - 其他所有 profile（prod / staging / test / 未设置 / 随便编的）都强制运行 License。
 *
 * 这意味着：
 * - 生产环境改 profile=dev 会触发其他生产配置失效（数据库、日志级别等），代价极高。
 * - 改成任意非开发 profile（如 staging），License 仍然强制运行。
 * - 不设置 profile，License 默认强制（安全默认）。
 */
@Slf4j
@Component
public class LicenseGuard {

    private final LicenseProperties properties;
    private final Environment environment;

    public LicenseGuard(LicenseProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    /**
     * License 是否应该强制执行校验。
     * 白名单策略：只有 dev/local 才允许关闭，其余全部强制。
     */
    public boolean shouldEnforce() {
        boolean isDevProfile = environment.acceptsProfiles(Profiles.of("dev", "local"));

        if (isDevProfile) {
            if (properties.isEnabled()) {
                log.info("[LicenseGuard] 开发环境，enabled=true，License 正常运行");
                return true;
            }
            log.info("[LicenseGuard] 开发环境，enabled=false，License 已关闭");
            return false;
        }

        // 非开发环境：一律强制
        String activeProfiles = String.join(",", environment.getActiveProfiles());
        if (!properties.isEnabled()) {
            log.warn("[LicenseGuard] profile=[{}] enabled=false，但非开发环境，已强制覆盖为 true（代码级保护）", activeProfiles);
        } else {
            log.info("[LicenseGuard] profile=[{}] License 正常运行", activeProfiles);
        }
        return true;
    }
}
