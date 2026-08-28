package com.panjia.license.exception;

/**
 * License 体系统一异常。
 * 所有 License 相关错误由此异常承载，全局异常处理器统一转译。
 */
public class LicenseException extends RuntimeException {

    public LicenseException(String message) {
        super(message);
    }

    public LicenseException(String message, Throwable cause) {
        super(message, cause);
    }
}
