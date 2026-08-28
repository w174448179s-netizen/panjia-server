package com.panjia.license.security;

import com.panjia.license.enums.OperationEnum;
import com.panjia.license.enums.TriggerCodeEnum;
import com.panjia.license.exception.LicenseException;
import com.panjia.license.exception.RestrictedModeException;
import com.panjia.license.service.LicenseContext;
import com.panjia.license.service.LicenseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * License 多点散布校验入口。
 *
 * 设计目的：不依赖单一拦截器，业务代码在关键路径直接调用此入口。
 * 攻击者要绕过校验，需要找到并修改所有调用点，成本远高于改一个拦截器。
 *
 * 使用方式（业务层）：
 *   @Autowired LicenseCheckPoint licenseCheckPoint;
 *
 *   public SalaryResult calculateSalary(...) {
 *       licenseCheckPoint.requireLicense(OperationEnum.CALCULATE);
 *       // ... 算薪逻辑
 *   }
 *
 *   public void exportReport(...) {
 *       licenseCheckPoint.requireLicense(OperationEnum.EXPORT);
 *       // ... 导出逻辑
 *   }
 *
 * 校验链路：
 *   1. LicenseGuard.shouldEnforce() → 是否需要校验
 *   2. IntegrityChecker.quickCheck() → 代码完整性快检
 *   3. LicenseService.isOperationAllowed() → 操作授权校验
 *   4. LicenseContext.isRestricted() → 受限模式检查
 *
 * 任何一步失败均抛异常，阻止业务执行。
 */
@Slf4j
@Component
public class LicenseCheckPoint {

    private final LicenseGuard licenseGuard;
    private final IntegrityChecker integrityChecker;
    private final LicenseService licenseService;
    private final LicenseContext licenseContext;
    private final RestrictedMode restrictedMode;

    public LicenseCheckPoint(LicenseGuard licenseGuard,
                             IntegrityChecker integrityChecker,
                             LicenseService licenseService,
                             LicenseContext licenseContext,
                             RestrictedMode restrictedMode) {
        this.licenseGuard = licenseGuard;
        this.integrityChecker = integrityChecker;
        this.licenseService = licenseService;
        this.licenseContext = licenseContext;
        this.restrictedMode = restrictedMode;
    }

    /**
     * 要求 License 校验通过才能执行操作。
     * 适用于业务层关键方法入口（算薪、导入、导出等）。
     *
     * @param operation 操作类型
     * @throws RestrictedModeException 受限模式或校验失败
     * @throws LicenseException License 异常
     */
    public void requireLicense(OperationEnum operation) {
        requireLicense(operation.name());
    }

    /**
     * 要求 License 校验通过（字符串操作名）。
     */
    public void requireLicense(String operation) {
        if (!licenseGuard.shouldEnforce()) {
            return;
        }

        // 1. 代码完整性快检（防 class 被篡改）
        if (!integrityChecker.quickCheck()) {
            log.error("[LicenseCheckPoint] 代码完整性校验失败，触发受限模式");
            restrictedMode.trigger(TriggerCodeEnum.T3_INTEGRITY, "完整性快检失败: " + operation);
            throw new RestrictedModeException(TriggerCodeEnum.T3_INTEGRITY.getCode(), "系统完整性校验失败，请联系服务商");
        }

        // 2. 受限模式 → 禁止核心操作
        if (licenseContext.isRestricted()) {
            log.warn("[LicenseCheckPoint] 受限模式，操作被拒绝: {}", operation);
            throw new RestrictedModeException(TriggerCodeEnum.T1_AUTH_FAIL.getCode(), "授权受限，核心功能不可用，请联系服务商");
        }

        // 3. 离线锁死 → 禁止所有核心操作
        if (licenseContext.getStatus() == com.panjia.license.enums.LicenseStatusEnum.OFFLINE_LOCK) {
            log.warn("[LicenseCheckPoint] 离线锁死，操作被拒绝: {}", operation);
            throw new RestrictedModeException(TriggerCodeEnum.T1_AUTH_FAIL.getCode(), "系统已锁定，请联系服务商恢复");
        }

        // 4. 操作授权校验
        if (!licenseService.isOperationAllowed(operation)) {
            log.warn("[LicenseCheckPoint] 操作授权失败: {}", operation);
            throw new RestrictedModeException(TriggerCodeEnum.T1_AUTH_FAIL.getCode(), "操作未被授权: " + operation);
        }

        log.debug("[LicenseCheckPoint] 操作校验通过: {}", operation);
    }

    /**
     * 仅校验完整性（不校验操作授权）。
     * 适用于非业务操作但需要确保系统未被篡改的场景。
     *
     * @throws RestrictedModeException 完整性校验失败
     */
    public void requireIntegrity() {
        if (!licenseGuard.shouldEnforce()) {
            return;
        }

        if (!integrityChecker.quickCheck()) {
            log.error("[LicenseCheckPoint] 代码完整性校验失败");
            restrictedMode.trigger(TriggerCodeEnum.T3_INTEGRITY, "完整性校验失败");
            throw new RestrictedModeException(TriggerCodeEnum.T3_INTEGRITY.getCode(), "系统完整性校验失败，请联系服务商");
        }
    }

    /**
     * 检查当前是否处于受限模式（不抛异常）。
     * 适用于 UI 层展示提示，不阻断流程。
     */
    public boolean isRestricted() {
        if (!licenseGuard.shouldEnforce()) {
            return false;
        }
        return licenseContext.isRestricted();
    }

    /**
     * 获取受限模式偏移量（算薪结果确定性偏移）。
     * 受限模式下算薪结果会引入确定性偏移，使结果"错得明显"。
     */
    public long getRestrictedOffset() {
        if (!licenseGuard.shouldEnforce() || !licenseContext.isRestricted()) {
            return 0;
        }
        return restrictedMode.computeDeterministicOffset();
    }
}
