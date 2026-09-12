package com.panjia.commission.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 结佣明细冲销原因（与业绩域 ReversedReason code 对齐，结佣域详细设计 §4.4）。
 * <p>
 * 存储约定：DB 字段 reversed_reason VARCHAR(32) 存 code（与枚举名一致），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 */
public enum ReversedReason {

    /** 批次替换 supersede（导入新批次替换旧批次） */
    SUPERSEDE("批次替换"),

    /** 重归一化 reNormalize（导入域重跑归一化） */
    RENORMALIZE("重归一化"),

    /** 结佣人工调整（DISCOUNT/VOID 调整单执行） */
    MANUAL_ADJUST("人工调整"),

    /** 业绩域期间作废 */
    PERIOD_VOID("期间作废");

    /** 原因码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 原因描述 */
    private final String desc;

    ReversedReason(String desc) {
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
     * 按 code 解析冲销原因（未知 code 返回 null，由调用方决定兜底策略）。
     *
     * @param code 原因码
     * @return 冲销原因；无法识别返回 null
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
