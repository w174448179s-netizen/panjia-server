package com.panjia.performance.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 业绩来源枚举。
 * <p>
 * 存储约定：DB 字段 source VARCHAR(16) 存 code（code 固定取枚举名），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 */
public enum PerformanceSource {

    /** 导入生成 */
    IMPORT("导入生成"),

    /** 手工录入 */
    MANUAL("手工录入");

    /** 来源码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 来源描述 */
    private final String desc;

    PerformanceSource(String desc) {
        this.code = name();
        this.desc = desc;
    }

    /**
     * 获取来源码。
     *
     * @return 来源码
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 获取来源描述。
     *
     * @return 描述文本
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按 code 解析业绩来源。
     *
     * @param code 来源码
     * @return 业绩来源；code 为空或无法识别时返回 null
     */
    public static PerformanceSource fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (PerformanceSource source : values()) {
            if (source.code.equals(code)) {
                return source;
            }
        }
        return null;
    }
}
