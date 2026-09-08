package com.panjia.license.security;

import com.panjia.license.LicenseMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * License 强制守卫。
 *
 * dev/prod 模式由编译时常量 LicenseMode.DEV 决定（构建时注入）：
 * - 生产构建：LicenseMode.DEV=false → shouldEnforce()=true → 完整授权校验
 * - 开发构建：LicenseMode.DEV=true → shouldEnforce()=false → 跳过强制校验
 *
 * 注意：LicenseMode.DEV 是编译时常量，prod 构建时编译器对
 * 所有 if (LicenseMode.DEV) 分支做死代码消除，dev 逻辑不存在于字节码。
 */
@Slf4j
@Component
public class LicenseGuard {

    public LicenseGuard() {
        if (LicenseMode.DEV) {
            log.info("[LicenseGuard] dev 模式（编译时常量），License 强制校验已跳过");
        } else {
            log.info("[LicenseGuard] prod 模式（编译时常量），License 完整校验");
        }
    }

    /**
     * 是否强制执行授权校验。
     * dev 模式返回 false（跳过），prod 模式返回 true（强制执行）。
     */
    public boolean shouldEnforce() {
        return !LicenseMode.DEV;
    }
}
