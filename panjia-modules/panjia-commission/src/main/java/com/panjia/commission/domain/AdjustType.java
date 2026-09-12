package com.panjia.commission.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 结佣调整类型（结佣域详细设计 §3.3）。
 * <p>
 * 存储约定：DB 字段 adjust_type VARCHAR(16) 存 code（与枚举名一致），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 */
public enum AdjustType {

    /** 折扣：旧明细 REVERSED + 新明细（amount = 折后值直接存储，非系数；种类如85折写入 reason） */
    DISCOUNT("折扣"),

    /** 差额补发：新增差额明细（performance_fact_id = NULL，period = target_period） */
    DIFF("差额补发"),

    /** 作废：旧明细 REVERSED */
    VOID("作废");

    /** 类型码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 类型描述 */
    private final String desc;

    AdjustType(String desc) {
        this.code = name();
        this.desc = desc;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 按 code 解析调整类型。
     *
     * @param code 类型码
     * @return 调整类型；无法识别返回 null
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
