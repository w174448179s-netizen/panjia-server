package com.panjia.license.security;

import com.panjia.license.config.LicenseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * License 强制守卫。
 *
 * 安全策略：
 * - 始终返回 true，所有环境都执行 License 校验。
 * - dev 环境通过预置 dev token + 跳过远程心跳实现"本地自洽"。
 * - 生产环境需要真实激活 token + 远程心跳。
 *
 * 这意味着所有环境走同一套代码路径，License 代码在开发时就被验证。
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
     * dev 环境通过 devMode + devToken 实现本地自洽，不跳过校验逻辑。
     */
    public boolean shouldEnforce() {
        if (properties.isDevMode()) {
            log.info("[LicenseGuard] 开发模式，使用预置 dev token，License 校验正常运行（跳过远程心跳）");
        } else {
            String activeProfiles = String.join(",", environment.getActiveProfiles());
            log.info("[LicenseGuard] profile=[{}] License 正常运行", activeProfiles);
        }
        return true;
    }
}
