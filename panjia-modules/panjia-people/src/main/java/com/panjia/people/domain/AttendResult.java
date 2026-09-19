package com.panjia.people.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 日考勤结果枚举。
 * <p>
 * 存储约定：DB 字段 attend_result VARCHAR(16) 存 code（code 固定取枚举名），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 */
public enum AttendResult {

    /** 正常 */
    NORMAL("正常"),

    /** 迟到 */
    LATE("迟到"),

    /** 早退 */
    EARLY("早退"),

    /** 迟到并早退 */
    LATE_EARLY("迟到并早退"),

    /** 缺卡 */
    MISSING_CARD("缺卡"),

    /** 旷工 */
    ABSENT("旷工"),

    /** 休息 */
    REST("休息"),

    /** 请假 */
    LEAVE("请假"),

    /** 出差 */
    BUSINESS_TRIP("出差"),

    /** 外出 */
    OUT("外出");

    /** 结果码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 结果描述 */
    private final String desc;

    AttendResult(String desc) {
        this.code = name();
        this.desc = desc;
    }

    /**
     * 获取结果码。
     *
     * @return 结果码
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 获取结果描述。
     *
     * @return 描述文本
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按 code 解析考勤结果。
     *
     * @param code 结果码
     * @return 结果枚举；code 为空或无法识别时返回 null
     */
    public static AttendResult fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (AttendResult result : values()) {
            if (result.code.equals(code)) {
                return result;
            }
        }
        return null;
    }
}
