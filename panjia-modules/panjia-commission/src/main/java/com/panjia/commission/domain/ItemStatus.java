package com.panjia.commission.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 结佣明细状态（三态状态机，非闭开区间，ADR B7）。
 * <p>
 * 关键不变量：已审批（APPROVED）明细金额不可变；REVERSED 为终态且永久保留。
 * <p>
 * 存储约定：DB 字段 status VARCHAR(16) 存 code（与枚举名一致），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 */
public enum ItemStatus {

    /** 待审批 */
    PENDING("待审批"),

    /** 已审批（金额冻结，进工资） */
    APPROVED("已审批"),

    /** 已冲销（终态，永久保留） */
    REVERSED("已冲销");

    /** 状态码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 状态描述 */
    private final String desc;

    ItemStatus(String desc) {
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
     * 按 code 解析明细状态。
     *
     * @param code 状态码
     * @return 明细状态；无法识别返回 null
     */
    public static ItemStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (ItemStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
