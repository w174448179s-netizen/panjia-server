package com.panjia.license.security;

import com.panjia.license.LicenseMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * License 强制守卫。
 *
 * dev/prod 模式由编译时常量 LicenseMode.DEV 决定（构建时注入）：
 * - 生产构建：LicenseMode.DEV=false → 编译器消除所有 dev 分支 → dev 逻辑不存在于字节码
 * - 开发构建：LicenseMode.DEV=true → dev 分支保留
 *
 * 没有 isDevMode() 运行时方法，没有 properties 文件读取，
 * 不读 Spring profile，不读 yml，不读环境变量。
 */
@Slf4j
@Component
public class LicenseGuard {

    public LicenseGuard() {
        if (LicenseMode.DEV) {
            log.info("[LicenseGuard] dev 模式（编译时常量），License 校验运行但跳过远程调用");
        } else {
            log.info("[LicenseGuard] prod 模式（编译时常量），License 完整校验");
        }
    }

    /**
     * 始终返回 true。所有环境都执行 License 校验。
     */
    public boolean shouldEnforce() {
        return true;
    }
}
