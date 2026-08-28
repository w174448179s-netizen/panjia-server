package com.panjia.license.exception;

/**
 * 受限模式触发异常。
 * 标识系统已进入受限模式（算薪结果将错误），用于告警日志记录。
 */
public class RestrictedModeException extends LicenseException {

    /** 触发原因码 */
    private final String triggerCode;

    public RestrictedModeException(String triggerCode, String message) {
        super(message);
        this.triggerCode = triggerCode;
    }

    public String getTriggerCode() {
        return triggerCode;
    }
}
