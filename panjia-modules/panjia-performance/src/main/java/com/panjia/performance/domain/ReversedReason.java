package com.panjia.performance.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 业绩冲销原因枚举。
 * <p>
 * 存储约定：DB 字段 reversed_reason VARCHAR(16) 存 code（code 固定取枚举名），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 */
public enum ReversedReason {

    /** 替换（新版本覆盖） */
    SUPERSEDE("替换(新版本覆盖)"),

    /** 重归一化 */
    RENORMALIZE("重归一化"),

    /** 手工调整 */
    MANUAL_ADJUST("手工调整"),

    /** 期间作废 */
    PERIOD_VOID("期间作废");

    /** 冲销原因码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 冲销原因描述 */
    private final String desc;

    ReversedReason(String desc) {
        this.code = name();
        this.desc = desc;
    }

    /**
     * 获取冲销原因码。
     *
     * @return 原因码
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 获取冲销原因描述。
     *
     * @return 描述文本
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按 code 解析冲销原因。
     *
     * @param code 原因码
     * @return 冲销原因；code 为空或无法识别时返回 null
     */
    public static ReversedReason fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (ReversedReason reason : values()) {
            if (reason.code.equals(code)) {
                return reason;
            }
        }
        return null;
    }
}
