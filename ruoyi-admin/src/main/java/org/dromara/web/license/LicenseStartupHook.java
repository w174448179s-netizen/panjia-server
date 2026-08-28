package org.dromara.web.license;

import com.panjia.license.security.LicenseCheckPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * License 启动硬依赖钩子。
 *
 * 作用：在 admin 启动时注入 LicenseCheckPoint，形成硬依赖。
 * 如果 panjia-license 模块被移除，Spring 启动时找不到 LicenseCheckPoint bean，
 * 应用直接报 NoSuchBeanDefinitionException 无法启动。
 *
 * dev 模式下 LicenseCheckPoint.requireIntegrity() 内部 LicenseGuard.shouldEnforce() 返回 false，
 * 直接跳过，不影响开发。
 */
@Slf4j
@Component
public class LicenseStartupHook {

    private final LicenseCheckPoint licenseCheckPoint;

    public LicenseStartupHook(LicenseCheckPoint licenseCheckPoint) {
        this.licenseCheckPoint = licenseCheckPoint;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        // 硬依赖验证：调用一次确保 License 模块存在
        // dev 模式下内部直接 return，prod 下做完整性快检
        licenseCheckPoint.requireIntegrity();
        log.info("[LicenseStartupHook] License 模块加载验证通过");
    }
}
