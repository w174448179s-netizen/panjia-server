package com.panjia.commission.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 结佣消费日志状态（结佣域详细设计 §3.4）。
 * <p>
 * 存储约定：DB 字段 status VARCHAR(16) 存 code（与枚举名一致），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 */
public enum ConsumeStatus {

    SUCCESS("成功"),
    PARTIAL("部分成功"),
    FAILED("失败");

    /** 状态码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 状态描述 */
    private final String desc;

    ConsumeStatus(String desc) {
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
     * 按 code 解析消费状态。
     *
     * @param code 状态码
     * @return 消费状态；无法识别返回 null
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
}
