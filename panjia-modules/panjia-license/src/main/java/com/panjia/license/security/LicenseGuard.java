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
 *   - prod profile 激活时，License 强制开启，无视 panjia.license.enabled=false 配置。
 *   - 非 prod profile（dev/local/test）时，尊重 enabled 配置，开发可关闭。
 *
 * 这意味着：即使在生产环境有人修改 yml 或注入环境变量 PANJIA_LICENSE_ENABLED=false，
 * 只要 Spring profile=prod，License 校验仍然强制运行。
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
     * prod 环境永远返回 true，非 prod 环境跟随 enabled 配置。
     */
    public boolean shouldEnforce() {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            if (!properties.isEnabled()) {
                log.warn("[LicenseGuard] 检测到 prod profile 但 enabled=false，已强制覆盖为 true（代码级保护）");
            }
            return true;
        }
        return properties.isEnabled();
    }
}
