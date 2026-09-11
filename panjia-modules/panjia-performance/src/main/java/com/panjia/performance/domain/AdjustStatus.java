package com.panjia.performance.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 业绩调整单状态枚举。
 * <p>
 * 状态流转：
 * <ul>
 *   <li>{@link #SUBMITTED} → {@link #APPROVED} / {@link #REJECTED} / {@link #CANCELLED}</li>
 *   <li>{@link #APPROVED} → {@link #EXECUTED}</li>
 *   <li>终态：{@link #REJECTED} / {@link #CANCELLED} / {@link #EXECUTED}</li>
 * </ul>
 * <p>
 * 存储约定：DB 字段 status VARCHAR(16) 存 code（code 固定取枚举名），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 */
public enum AdjustStatus {

    /** 已提交 */
    SUBMITTED("已提交"),

    /** 审批通过 */
    APPROVED("审批通过"),

    /** 已拒绝（终态） */
    REJECTED("已拒绝"),

    /** 已取消（终态） */
    CANCELLED("已取消"),

    /** 已执行（终态） */
    EXECUTED("已执行");

    /** 状态码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 状态描述 */
    private final String desc;

    AdjustStatus(String desc) {
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

    /**
     * 判断是否可流转到目标状态。
     * <p>
     * 合法流转：
     * SUBMITTED → APPROVED / REJECTED / CANCELLED；
     * APPROVED → EXECUTED；
     * 终态不可再流转，不允许自流转。
     *
     * @param target 目标状态
     * @return true 表示允许流转
     */
    public boolean canTransitTo(AdjustStatus target) {
        if (this == target) {
            return false;
        }
        if (this == SUBMITTED) {
            return target == APPROVED || target == REJECTED || target == CANCELLED;
        }
        if (this == APPROVED) {
            return target == EXECUTED;
        }
        return false;
    }

    /**
     * 是否终态（REJECTED / CANCELLED / EXECUTED 不可再流转）。
     *
     * @return true 表示终态
     */
    public boolean isTerminal() {
        return this == REJECTED || this == CANCELLED || this == EXECUTED;
    }

    /**
     * 是否进行中（SUBMITTED / APPROVED：尚未终结）。
     *
     * @return true 表示进行中
     */
    public boolean isRunning() {
        return this == SUBMITTED || this == APPROVED;
    }
}
