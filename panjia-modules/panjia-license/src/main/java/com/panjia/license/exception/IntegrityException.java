package com.panjia.license.exception;

/**
 * 完整性校验异常。
 * 启动期或算薪前 class 校验失败时抛出，触发受限模式（T3）。
 */
public class IntegrityException extends LicenseException {

    public IntegrityException(String message) {
        super(message);
    }

    public IntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
