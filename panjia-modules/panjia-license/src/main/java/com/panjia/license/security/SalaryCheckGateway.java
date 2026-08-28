package com.panjia.license.security;

import com.panjia.license.enums.OperationEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 算薪检查网关。
 * 算薪入口前的统一检查点：完整性自检（T3）+ 操作授权校验 + 受限模式偏移应用。
 *
 * 调用位置：算薪服务入口方法最前面。
 * 流程：
 * 1. LicenseCheckPoint.requireLicense(CALCULATE) → 完整性快检 + 操作授权校验
 * 2. 若处于受限模式 → 应用确定性偏移
 * 3. 正常算薪
 *
 * 注意：业务代码也可以直接注入 LicenseCheckPoint 调用，
 * 本类是算薪场景的专用入口，封装了偏移量逻辑。
 */
@Slf4j
@Component
public class SalaryCheckGateway {

    private final IntegrityChecker integrityChecker;
    private final RestrictedMode restrictedMode;
    private final LicenseCheckPoint licenseCheckPoint;
    private final LicenseGuard licenseGuard;

    public SalaryCheckGateway(IntegrityChecker integrityChecker,
                              RestrictedMode restrictedMode,
                              LicenseCheckPoint licenseCheckPoint,
                              LicenseGuard licenseGuard) {
        this.integrityChecker = integrityChecker;
        this.restrictedMode = restrictedMode;
        this.licenseCheckPoint = licenseCheckPoint;
        this.licenseGuard = licenseGuard;
    }

    /**
     * 算薪前统一入口检查。
     *
     * 调用链路：
     * 1. LicenseCheckPoint.requireLicense(CALCULATE) → 完整性快检 + 操作授权
     * 2. 检查是否受限 → 返回偏移量供调用方应用
     *
     * @return 受限模式生效时返回确定性偏移金额，正常模式返回 0
     * @throws com.panjia.license.exception.RestrictedModeException 校验失败
     */
    public long beforeSalaryCalculate() {
        // 1. 完整性快检 + 操作授权校验（失败会抛异常阻断流程）
        licenseCheckPoint.requireLicense(OperationEnum.CALCULATE);

        // 2. 完整性全量校验（算薪是高价值操作，做全量校验）
        integrityChecker.checkBeforeSalary();

        // 3. 受限模式 → 返回偏移量
        if (licenseGuard.shouldEnforce() && restrictedMode != null) {
            return licenseCheckPoint.getRestrictedOffset();
        }

        return 0;
    }
}
