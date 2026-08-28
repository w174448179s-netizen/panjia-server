package com.panjia.license.starter;

import com.panjia.license.config.LicenseProperties;
import com.panjia.license.enums.LicenseStatusEnum;
import com.panjia.license.exception.LicenseException;
import com.panjia.license.exception.MonotonicException;
import com.panjia.license.security.IntegrityChecker;
import com.panjia.license.security.LicenseGuard;
import com.panjia.license.security.RestrictedMode;
import com.panjia.license.service.LicenseService;
import com.panjia.license.service.MultiInstanceDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时执行 License 校验。
 * 非 prod 且 enabled=false 时跳过，prod 下强制执行。
 *
 * 校验链路：
 * 1. 完整性自检（T3 触发点）→ 防 class 被篡改
 * 2. 多实例检测 → 防拉黑机器启动
 * 3. 授权状态校验 → 激活/心跳/过期
 */
@Slf4j
@Component
public class LicenseStartupValidator {

    private final LicenseService licenseService;
    private final LicenseProperties properties;
    private final RestrictedMode restrictedMode;
    private final MultiInstanceDetector multiInstanceDetector;
    private final LicenseGuard licenseGuard;
    private final IntegrityChecker integrityChecker;

    public LicenseStartupValidator(LicenseService licenseService,
                                   LicenseProperties properties,
                                   RestrictedMode restrictedMode,
                                   MultiInstanceDetector multiInstanceDetector,
                                   LicenseGuard licenseGuard,
                                   IntegrityChecker integrityChecker) {
        this.licenseService = licenseService;
        this.properties = properties;
        this.restrictedMode = restrictedMode;
        this.multiInstanceDetector = multiInstanceDetector;
        this.licenseGuard = licenseGuard;
        this.integrityChecker = integrityChecker;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!licenseGuard.shouldEnforce()) {
            log.info("[LicenseStartupValidator] License 校验已关闭（非 prod 环境，enabled=false）");
            return;
        }

        log.info("[LicenseStartupValidator] ========== License 启动校验开始 ==========");

        // 1. 完整性自检（T3 触发点）— 第一步，防 class 被篡改后绕过后续校验
        try {
            integrityChecker.checkStartup();
            log.info("[LicenseStartupValidator] 代码完整性校验通过");
        } catch (Exception e) {
            log.error("[LicenseStartupValidator] 代码完整性校验失败: {}", e.getMessage());
            restrictedMode.trigger("T3_INTEGRITY_FAIL", "启动完整性校验失败: " + e.getMessage());
            log.info("[LicenseStartupValidator] ========== License 启动校验完成（受限模式） ==========");
            return;
        }

        // 2. 多实例检测
        if (multiInstanceDetector.isBlacklisted()) {
            log.warn("[LicenseStartupValidator] 检测到拉黑标记，直接进入受限模式");
            restrictedMode.trigger("T2_SERVER_REVOKED", "启动检测到拉黑标记");
            log.info("[LicenseStartupValidator] ========== License 启动校验完成（受限模式） ==========");
            return;
        }

        // 3. 授权状态校验
        try {
            LicenseStatusEnum status = licenseService.getCurrentStatus();
            log.info("[LicenseStartupValidator] 当前授权状态: {}", status);

            if (status == LicenseStatusEnum.NOT_ACTIVATED) {
                log.warn("[LicenseStartupValidator] 尚未激活，进入受限模式（只读）");
                restrictedMode.trigger("T4_NOT_ACTIVATED", "尚未激活");
            } else if (status == LicenseStatusEnum.EXPIRED) {
                log.warn("[LicenseStartupValidator] License 已过期");
            } else if (licenseService.isRestricted()) {
                log.warn("[LicenseStartupValidator] 处于受限模式");
            }

            log.info("[LicenseStartupValidator] ========== License 启动校验完成 ==========");
        } catch (MonotonicException e) {
            log.error("[LicenseStartupValidator] 单调时钟校验失败，进入受限模式: {}", e.getMessage());
            restrictedMode.trigger("T1_CLOCK_TAMPER", e.getMessage());
        } catch (LicenseException e) {
            log.error("[LicenseStartupValidator] License 启动校验失败: {}", e.getMessage());
            restrictedMode.trigger("T3_INTEGRITY_FAIL", e.getMessage());
        } catch (Exception e) {
            log.error("[LicenseStartupValidator] 未预期异常: {}", e.getMessage(), e);
            restrictedMode.trigger("T3_INTEGRITY_FAIL", "启动校验异常");
        }
    }
}
