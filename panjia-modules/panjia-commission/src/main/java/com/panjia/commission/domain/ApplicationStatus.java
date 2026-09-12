package com.panjia.commission.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 结佣申请单状态。
 * <p>
 * 状态机（结佣域详细设计 §3.1）：
 * <pre>
 * DRAFT ──提交──▶ SUBMITTED ──审批通过──▶ APPROVED ──锁定──▶ LOCKED（终态）
 *                     │                        │
 *                     └──驳回──▶ REJECTED      └──作废──▶ CANCELLED
 * </pre>
 * 简化审批实现：审批回调在单事务内完成 SUBMITTED → LOCKED（内部先 APPROVED 落 approved_month）。
 * <p>
 * 存储约定：DB 字段 status VARCHAR(16) 存 code（与枚举名一致），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 */
public enum ApplicationStatus {

    DRAFT("草稿"),
    SUBMITTED("已提交"),
    APPROVED("已通过"),
    LOCKED("已锁定"),
    REJECTED("已驳回"),
    CANCELLED("已作废");

    /** 状态码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 状态描述 */
    private final String desc;

    ApplicationStatus(String desc) {
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
     * 按 code 解析申请单状态。
     *
     * @param code 状态码
     * @return 申请单状态；无法识别返回 null
     */
    public static ApplicationStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (ApplicationStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
