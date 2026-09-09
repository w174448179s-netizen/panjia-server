package com.panjia.people.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 员工状态枚举（V5.2）。
 * <p>
 * 存储约定：DB 字段 status VARCHAR(16) 存 code（code 固定取枚举名），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 */
public enum EmployeeStatus {

    /** 在职（全职） */
    ACTIVE("在职"),

    /** 兼职 */
    PARTTIME("兼职"),

    /** 离职（账户禁用，无删除接口） */
    LEFT("离职"),

    /** 待入职 */
    PENDING("待入职");

    /** 状态码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 状态描述 */
    private final String desc;

    EmployeeStatus(String desc) {
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
    public static EmployeeStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (EmployeeStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
