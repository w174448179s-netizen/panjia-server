package com.panjia.performance.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 业绩调整类型枚举。
 * <p>
 * 存储约定：DB 字段 adjust_type VARCHAR(16) 存 code（code 固定取枚举名），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 */
public enum AdjustType {

    /** 金额调整 */
    AMOUNT("金额调整"),

    /** 业绩冲销 */
    VOID("业绩冲销"),

    /** 部门划转 */
    TRANSFER("部门划转");

    /** 调整类型码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 调整类型描述 */
    private final String desc;

    AdjustType(String desc) {
        this.code = name();
        this.desc = desc;
    }

    /**
     * 获取调整类型码。
     *
     * @return 类型码
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 获取调整类型描述。
     *
     * @return 描述文本
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按 code 解析调整类型。
     *
     * @param code 类型码
     * @return 调整类型；code 为空或无法识别时返回 null
     */
    public static AdjustType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (AdjustType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
