package com.panjia.license.exception;

/**
 * 单调时钟异常。
 * 时间回拨、文件缺失等异常场景的统一承载。
 */
public class MonotonicException extends LicenseException {

    public MonotonicException(String message) {
        super(message);
    }
}
