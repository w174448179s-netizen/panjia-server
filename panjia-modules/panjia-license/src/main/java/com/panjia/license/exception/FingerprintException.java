package com.panjia.license.exception;

/**
 * 指纹采集异常。
 * 如 /etc/machine-id 缺失、无法读取等，属于部署环境问题，需联系部署人员。
 */
public class FingerprintException extends LicenseException {

    public FingerprintException(String message) {
        super(message);
    }
}
