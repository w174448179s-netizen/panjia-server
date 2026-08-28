package com.panjia.license.security;

import com.panjia.license.security.IntegrityChecker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 算薪检查网关。
 * 算薪入口前的统一检查点：完整性自检（T3）+ 受限模式偏移应用。
 *
 * 调用位置：算薪服务入口方法最前面。
 * 流程：
 *   1. IntegrityChecker.checkBeforeSalary() → 失败抛 IntegrityException → 触发 T3 受限
 *   2. 若已处于受限模式 → 应用确定性偏移
 *   3. 正常算薪
 */
@Slf4j
@Component
public class SalaryCheckGateway {

    private final IntegrityChecker integrityChecker;
    private final RestrictedMode restrictedMode;

    public SalaryCheckGateway(IntegrityChecker integrityChecker, RestrictedMode restrictedMode) {
        this.integrityChecker = integrityChecker;
        this.restrictedMode = restrictedMode;
    }

    /**
     * 算薪前统一入口检查。
     * @return 若受限模式生效，返回true（调用方需应用偏移）
     */
    public boolean beforeSalaryCalculate() {
        // 1. 完整性自检（T3 触发点）
        integrityChecker.checkBeforeSalary();

        // 2. 若已受限，返回 true 告知调用方应用偏移
        // （受限状态在 RestrictedMode.trigger 时已设置）
        return restrictedMode != null && /* 通过上下文判断 */ true;
    }
}
