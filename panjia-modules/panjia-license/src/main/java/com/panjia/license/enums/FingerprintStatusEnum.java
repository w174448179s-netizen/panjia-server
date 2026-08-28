package com.panjia.license.enums;

/**
 * 指纹状态枚举。
 * 用于多实例检测与换机流程中的指纹生命周期管理。
 */
public enum FingerprintStatusEnum {

    /** 正常活跃 */
    ACTIVE("活跃"),

    /** 已标记失效（换机流程同步生效） */
    REVOKED("已失效"),

    /** 已被拉黑（多实例并发被自动捕获） */
    BLACKLISTED("已拉黑"),

    /** 待激活 */
    PENDING("待激活");

    private final String description;

    FingerprintStatusEnum(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
