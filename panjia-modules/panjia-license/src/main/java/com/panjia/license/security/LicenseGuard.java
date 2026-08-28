package com.panjia.license.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Properties;

/**
 * License 强制守卫。
 *
 * dev/prod 模式由构建时 Maven 注入，不是运行时配置：
 * - 开发构建（mvn -P dev package）→ JAR 内 panjia-license-mode.properties 写 mode=dev
 * - 生产构建（mvn package）        → JAR 内写 mode=prod
 *
 * 客户拿到生产 JAR → properties 烙死 mode=prod → dev 模式永不激活。
 * 改 yml / 环境变量 / Spring profile 均无效，因为不读这些。
 * 要改必须解压 JAR 替换 properties → 重新打包（攻击成本高）。
 */
@Slf4j
@Component
public class LicenseGuard {

    private final boolean devMode;

    public LicenseGuard() {
        this.devMode = loadBakedMode();
        if (devMode) {
            log.info("[LicenseGuard] dev 模式（构建时注入），License 校验运行但跳过远程调用");
        } else {
            log.info("[LicenseGuard] prod 模式（构建时注入），License 完整校验");
        }
    }

    /**
     * 始终返回 true。所有环境都执行 License 校验。
     */
    public boolean shouldEnforce() {
        return true;
    }

    /**
     * 是否为开发模式。读 JAR 内嵌的 panjia-license-mode.properties（构建时注入）。
     * 不读 Spring profile，不读 yml，不读环境变量。
     */
    public boolean isDevMode() {
        return devMode;
    }

    /**
     * 从 classpath 加载构建时注入的 mode 标志。
     * 此文件在 JAR 内部，运行时不可修改。
     */
    private static boolean loadBakedMode() {
        try (InputStream is = LicenseGuard.class.getClassLoader()
                .getResourceAsStream("panjia-license-mode.properties")) {
            if (is == null) {
                log.warn("[LicenseGuard] panjia-license-mode.properties 未找到，默认 prod 模式");
                return false;
            }
            Properties props = new Properties();
            props.load(is);
            return "dev".equals(props.getProperty("license.mode"));
        } catch (Exception e) {
            log.warn("[LicenseGuard] 加载 license mode 失败: {}，默认 prod 模式", e.getMessage());
            return false;
        }
    }
}
