package com.panjia.license.domain;

/**
 * 指纹因子枚举。
 * 明确声明仅双因子，禁止新增（铁律 7）。
 */
public enum FingerprintFactor {

    /** 主因子：宿主机 machine-id */
    HOST_MACHINE_ID("hostMachineId"),

    /** 辅因子：数据卷 instanceId */
    INSTANCE_ID("instanceId");

    private final String fieldName;

    FingerprintFactor(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getFieldName() {
        return fieldName;
    }
}
