package com.panjia.license.starter;

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
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时执行 License 校验。
 *
 * 校验链路：
 * 1. 完整性自检（T3 触发点）→ 防 class 被篡改
 * 2. 多实例检测 → 防拉黑机器启动
 * 3. 授权状态校验 → 激活/心跳/过期
 *
 * 硬失败策略：
 * - 完整性校验失败 → 拒绝启动
 * - 服务端拉黑 → 拒绝启动
 * - 未激活 → 拒绝启动
 * - License 已过期 → 拒绝启动
 *
 * 实现方式：ApplicationReadyEvent 阶段抛异常不会阻止启动，
 * 故通过 ConfigurableApplicationContext.close() 触发上下文关闭实现硬失败。
 */
@Slf4j
@Component
public class LicenseStartupValidator {

    private final LicenseService licenseService;
    private final RestrictedMode restrictedMode;
    private final MultiInstanceDetector multiInstanceDetector;
    private final LicenseGuard licenseGuard;
    private final IntegrityChecker integrityChecker;
    private final ApplicationContext applicationContext;

    public LicenseStartupValidator(LicenseService licenseService,
                                   RestrictedMode restrictedMode,
                                   MultiInstanceDetector multiInstanceDetector,
                                   LicenseGuard licenseGuard,
                                   IntegrityChecker integrityChecker,
                                   ApplicationContext applicationContext) {
        this.licenseService = licenseService;
        this.restrictedMode = restrictedMode;
        this.multiInstanceDetector = multiInstanceDetector;
        this.licenseGuard = licenseGuard;
        this.integrityChecker = integrityChecker;
        this.applicationContext = applicationContext;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!licenseGuard.shouldEnforce()) {
            log.info("[LicenseStartupValidator] License 校验已跳过");
            return;
        }

        log.info("[LicenseStartupValidator] ========== License 启动校验开始 ==========");

        // 1. 完整性自检（T3 触发点）— 第一步，防 class 被篡改后绕过后续校验
        try {
            integrityChecker.checkStartup();
            log.info("[LicenseStartupValidator] 代码完整性校验通过");
        } catch (Exception e) {
            log.error("[LicenseStartupValidator] 代码完整性校验失败，拒绝启动: {}", e.getMessage());
            restrictedMode.trigger("T3_INTEGRITY_FAIL", "启动完整性校验失败: " + e.getMessage());
            shutdownApplication();
            return;
        }

        // 2. 多实例检测
        if (multiInstanceDetector.isBlacklisted()) {
            log.error("[LicenseStartupValidator] 检测到拉黑标记，拒绝启动");
            restrictedMode.trigger("T2_SERVER_REVOKED", "启动检测到拉黑标记");
            shutdownApplication();
            return;
        }

        // 3. 授权状态校验
        try {
            LicenseStatusEnum status = licenseService.getCurrentStatus();
            log.info("[LicenseStartupValidator] 当前授权状态: {}", status);

            // 双重保险：即使 status 字段被误设为 NORMAL，只要 token 为空就拒绝启动
            // 防止"未加载 token 却因默认值错误通过校验"的情况
            if (licenseService.getToken() == null) {
                log.error("[LicenseStartupValidator] 未检测到有效 token，拒绝启动");
                restrictedMode.trigger("T4_NOT_ACTIVATED", "未检测到有效 token");
                shutdownApplication();
                return;
            }

            if (status == LicenseStatusEnum.NOT_ACTIVATED) {
                log.error("[LicenseStartupValidator] 尚未激活，拒绝启动");
                restrictedMode.trigger("T4_NOT_ACTIVATED", "尚未激活");
                shutdownApplication();
                return;
            } else if (status == LicenseStatusEnum.EXPIRED || licenseService.isTokenExpired()) {
                // 双重保险：status==EXPIRED 或 token 实际过期（exp 或 licenseExpireAt）
                // decodeToken 只校验 JWT exp，自定义 licenseExpireAt 过期需在此拦截
                log.error("[LicenseStartupValidator] License 已过期，拒绝启动");
                restrictedMode.trigger("T1_AUTH_FAIL", "License 已过期");
                shutdownApplication();
                return;
            } else if (licenseService.isRestricted()) {
                log.warn("[LicenseStartupValidator] 处于受限模式，核心操作将被拦截");
            }

            log.info("[LicenseStartupValidator] ========== License 启动校验完成 ==========");
        } catch (MonotonicException e) {
            log.error("[LicenseStartupValidator] 单调时钟校验失败，拒绝启动: {}", e.getMessage());
            restrictedMode.trigger("T1_CLOCK_TAMPER", e.getMessage());
            shutdownApplication();
        } catch (LicenseException e) {
            log.error("[LicenseStartupValidator] License 启动校验失败，拒绝启动: {}", e.getMessage());
            restrictedMode.trigger("T3_INTEGRITY_FAIL", e.getMessage());
            shutdownApplication();
        } catch (Exception e) {
            log.error("[LicenseStartupValidator] 未预期异常，拒绝启动: {}", e.getMessage(), e);
            restrictedMode.trigger("T3_INTEGRITY_FAIL", "启动校验异常");
            shutdownApplication();
        }
    }

    /**
     * 关闭应用上下文实现硬失败。
     * ApplicationReadyEvent 阶段抛异常不会阻止启动，
     * 需通过主动关闭上下文让 Spring Boot 进程退出。
     */
    private void shutdownApplication() {
        log.error("[LicenseStartupValidator] ========== License 启动校验失败，应用关闭 ==========");
        if (applicationContext instanceof ConfigurableApplicationContext configurableContext) {
            configurableContext.close();
        } else {
            // 兜底：上下文不可配置时强制退出
            System.exit(1);
        }
    }
}
