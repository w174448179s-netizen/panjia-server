package com.panjia.license.exception;

import org.dromara.common.core.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * License 全局异常处理器。
 * 统一转换 License 异常为 R 对象，前端收到后提示对应信息。
 */
@Slf4j
@RestControllerAdvice
public class GlobalLicenseExceptionHandler {

    @ExceptionHandler(LicenseException.class)
    public R<Void> handleLicenseException(LicenseException e) {
        log.warn("[GlobalLicenseExceptionHandler] License 异常: {}", e.getMessage());
        if (e instanceof MonotonicException) {
            return R.fail("系统已锁定，请联系服务商恢复");
        } else if (e instanceof IntegrityException) {
            return R.fail("安全校验未通过，系统已进入受限模式");
        } else if (e instanceof FingerprintException) {
            return R.fail(e.getMessage());
        }
        return R.fail(e.getMessage());
    }

    @ExceptionHandler(RestrictedModeException.class)
    public R<Void> handleRestrictedModeException(RestrictedModeException e) {
        log.warn("[GlobalLicenseExceptionHandler] 受限模式: trigger={}, {}", e.getTriggerCode(), e.getMessage());
        return R.fail("系统已进入受限模式，请联系服务商");
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleGenericException(Exception e) {
        log.error("[GlobalLicenseExceptionHandler] 未预期异常: {}", e.getMessage(), e);
        return R.fail("系统内部错误，请联系服务商");
    }
}
