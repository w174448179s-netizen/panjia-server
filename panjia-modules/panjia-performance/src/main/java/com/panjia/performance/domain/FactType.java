package com.panjia.performance.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 业绩事实口径枚举。
 * <p>
 * 存储约定：DB 字段 fact_type VARCHAR(16) 存 code（code 固定取枚举名），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 */
public enum FactType {

    /** 结佣业绩（实收） */
    PERF_REAL("结佣业绩(实收)"),

    /** 新签业绩（应收） */
    PERF_EXPECT("新签业绩(应收)");

    /** 事实口径码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 事实口径描述 */
    private final String desc;

    FactType(String desc) {
        this.code = name();
        this.desc = desc;
    }

    /**
     * 获取事实口径码。
     *
     * @return 口径码
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 获取事实口径描述。
     *
     * @return 描述文本
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按 code 解析事实口径。
     *
     * @param code 口径码
     * @return 事实口径；code 为空或无法识别时返回 null
     */
    public static FactType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (FactType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
