package com.panjia.commission.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 结佣调整单状态（结佣域详细设计 §3.3）。
 * <p>
 * 状态机：SUBMITTED →（审批通过）EXECUTED（终态，同事务执行变更）／REJECTED／CANCELLED。
 * <p>
 * 存储约定：DB 字段 status VARCHAR(16) 存 code（与枚举名一致），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 */
public enum AdjustStatus {

    SUBMITTED("已提交"),
    APPROVED("已通过"),
    REJECTED("已驳回"),
    CANCELLED("已取消"),
    EXECUTED("已执行");

    /** 状态码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 状态描述 */
    private final String desc;

    AdjustStatus(String desc) {
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
     * 按 code 解析调整单状态。
     *
     * @param code 状态码
     * @return 调整单状态；无法识别返回 null
     */
    public static AdjustStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (AdjustStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
