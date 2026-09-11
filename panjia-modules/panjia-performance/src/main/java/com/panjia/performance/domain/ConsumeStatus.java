package com.panjia.performance.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 业绩消费状态枚举。
 * <p>
 * 状态说明：
 * <ul>
 *   <li>{@link #RUNNING} 进行中（消费处理中）</li>
 *   <li>{@link #SUCCESS} 成功（全部成功）</li>
 *   <li>{@link #PARTIAL} 部分成功（部分成功、部分失败）</li>
 *   <li>{@link #FAILED} 失败（全部失败）</li>
 * </ul>
 * <p>
 * 存储约定：DB 字段 status VARCHAR(16) 存 code（code 固定取枚举名），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 */
public enum ConsumeStatus {

    /** 进行中 */
    RUNNING("进行中"),

    /** 成功（终态） */
    SUCCESS("成功"),

    /** 部分成功（终态） */
    PARTIAL("部分成功"),

    /** 失败（终态） */
    FAILED("失败");

    /** 状态码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 状态描述 */
    private final String desc;

    ConsumeStatus(String desc) {
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
    public static ConsumeStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (ConsumeStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }

    /**
     * 是否终态（SUCCESS / PARTIAL / FAILED 为终态，不可再流转）。
     *
     * @return true 表示终态
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == PARTIAL || this == FAILED;
    }

    /**
     * 是否进行中（RUNNING：消费处理中，尚未终结）。
     *
     * @return true 表示进行中
     */
    public boolean isRunning() {
        return this == RUNNING;
    }
}
