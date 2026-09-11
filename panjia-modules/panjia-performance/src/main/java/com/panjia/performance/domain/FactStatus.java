package com.panjia.performance.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 业绩事实状态枚举（两态状态机）。
 * <p>
 * 状态流转：{@link #ACTIVE} → {@link #REVERSED}（冲销）；
 * {@link #REVERSED} 为终态，不可再流转。
 * <p>
 * 存储约定：DB 字段 fact_status VARCHAR(16) 存 code（code 固定取枚举名），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 */
public enum FactStatus {

    /** 有效 */
    ACTIVE("有效"),

    /** 已冲销（终态） */
    REVERSED("已冲销");

    /** 状态码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 状态描述 */
    private final String desc;

    FactStatus(String desc) {
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
    public static FactStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (FactStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }

    /**
     * 判断是否可流转到目标状态。
     * <p>
     * 合法流转：ACTIVE → REVERSED（冲销）；
     * 终态不可再流转，不允许自流转。
     *
     * @param target 目标状态
     * @return true 表示允许流转
     */
    public boolean canTransitTo(FactStatus target) {
        if (this == target) {
            return false;
        }
        if (this == ACTIVE) {
            return target == REVERSED;
        }
        return false;
    }

    /**
     * 是否终态（REVERSED 不可再流转）。
     *
     * @return true 表示终态
     */
    public boolean isTerminal() {
        return this == REVERSED;
    }

    /**
     * 是否进行中（ACTIVE：有效，尚未冲销）。
     *
     * @return true 表示进行中
     */
    public boolean isRunning() {
        return this == ACTIVE;
    }
}
