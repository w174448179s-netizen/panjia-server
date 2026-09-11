package com.panjia.performance.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 期间封账状态枚举。
 * <p>
 * 状态流转：{@link #OPEN} → {@link #CLOSED}（封账）；
 * {@link #CLOSED} 为终态，不可再流转。
 * <p>
 * 存储约定：DB 字段 status VARCHAR(16) 存 code（code 固定取枚举名），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 */
public enum PeriodCloseStatus {

    /** 开启 */
    OPEN("开启"),

    /** 已封账（终态） */
    CLOSED("已封账");

    /** 状态码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 状态描述 */
    private final String desc;

    PeriodCloseStatus(String desc) {
        this.code = name();
        this.desc = desc;
    }

    /**
     * 获取状态码。
     *
     * @return 状态码
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 获取状态描述。
     *
     * @return 描述文本
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按 code 解析状态枚举。
     *
     * @param code 状态码
     * @return 状态枚举；code 为空或无法识别时返回 null
     */
    public static PeriodCloseStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (PeriodCloseStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }

    /**
     * 判断是否可流转到目标状态。
     * <p>
     * 合法流转：OPEN → CLOSED（封账）；
     * 终态不可再流转，不允许自流转。
     *
     * @param target 目标状态
     * @return true 表示允许流转
     */
    public boolean canTransitTo(PeriodCloseStatus target) {
        if (this == target) {
            return false;
        }
        if (this == OPEN) {
            return target == CLOSED;
        }
        return false;
    }

    /**
     * 是否终态（CLOSED 不可再流转）。
     *
     * @return true 表示终态
     */
    public boolean isTerminal() {
        return this == CLOSED;
    }

    /**
     * 是否进行中（OPEN：开启，尚未封账）。
     *
     * @return true 表示进行中
     */
    public boolean isRunning() {
        return this == OPEN;
    }
}
