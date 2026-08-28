package com.panjia.license.security;

import com.panjia.license.config.LicenseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * License 强制守卫。
 *
 * dev 模式由 Spring profile 推导，不是配置项：
 * - profile=dev/local → dev 模式（跳过远程心跳，使用预置 dev token）
 * - 其他 profile → 生产模式（真实激活 + 远程心跳）
 *
 * 这意味着客户无法通过改 yml 配置进入 dev 模式，
 * 必须改 spring.profiles.active，而改 profile 会导致数据库、日志等全部变化。
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
     * 始终返回 true。所有环境都执行 License 校验。
     */
    public boolean shouldEnforce() {
        if (isDevMode()) {
            log.info("[LicenseGuard] dev profile，使用预置 dev token，License 校验正常运行（跳过远程心跳）");
        }
        return true;
    }

    /**
     * 是否为开发模式。由 Spring profile 推导，不可通过配置项覆盖。
     * 只有 dev / local 两个 profile 才返回 true。
     */
    public boolean isDevMode() {
        return environment.acceptsProfiles(Profiles.of("dev", "local"));
    }
}
