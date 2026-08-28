package com.panjia.license.starter;

import com.panjia.license.enums.TriggerCodeEnum;
import com.panjia.license.security.IntegrityChecker;
import com.panjia.license.security.LicenseGuard;
import com.panjia.license.security.RestrictedMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时完整性巡检调度器。
 *
 * 每小时执行一次全量完整性校验，检测运行时 class 被替换。
 * 与启动期校验互补：启动期只查一次，定时巡检覆盖运行期间。
 *
 * 失败时触发受限模式（T3），不抛异常中断调度。
 */
@Slf4j
@Component
public class IntegrityCheckScheduler {

    private final IntegrityChecker integrityChecker;
    private final LicenseGuard licenseGuard;
    private final RestrictedMode restrictedMode;

    public IntegrityCheckScheduler(IntegrityChecker integrityChecker,
                                   LicenseGuard licenseGuard,
                                   RestrictedMode restrictedMode) {
        this.integrityChecker = integrityChecker;
        this.licenseGuard = licenseGuard;
        this.restrictedMode = restrictedMode;
    }

    /**
     * 每小时全量完整性校验。
     * 固定延迟 1 小时，首次在启动后 1 小时执行。
     */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 3_600_000L)
    public void scheduledIntegrityCheck() {
        if (!licenseGuard.shouldEnforce()) {
            return;
        }

        try {
            log.debug("[IntegrityCheckScheduler] 定时完整性校验开始");
            integrityChecker.checkStartup();
            log.debug("[IntegrityCheckScheduler] 定时完整性校验通过");
        } catch (Exception e) {
            log.error("[IntegrityCheckScheduler] 定时完整性校验失败: {}", e.getMessage());
            restrictedMode.trigger(TriggerCodeEnum.T3_INTEGRITY, "定时完整性校验失败: " + e.getMessage());
        }
    }
}
