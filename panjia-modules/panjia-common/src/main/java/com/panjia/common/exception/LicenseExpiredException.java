package com.panjia.common.exception;

import java.io.Serial;

/**
 * License 过期异常
 * <p>
 * 当 License 完全过期或未授权时抛出。
 * 保护期内仅允许 GET 请求 + 导出操作。
 */
public class LicenseExpiredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public LicenseExpiredException(String message) {
        super(message);
    }

    public LicenseExpiredException() {
        super("License 已过期，请联系管理员续期");
    }
}
