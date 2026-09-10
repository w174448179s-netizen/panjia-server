package com.panjia.people.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 员工导入问题处理状态（V6.0 §1.7）。
 * <p>
 * 存储约定：DB 字段 status VARCHAR(16) 存 code（code 固定取枚举名）。
 */
public enum PeopleImportIssueStatus {

    /** 待处理（新问题默认） */
    OPEN("待处理"),

    /** 已解决 */
    RESOLVED("已解决"),

    /** 已忽略 */
    IGNORED("已忽略");

    /** 状态码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 状态描述 */
    private final String desc;

    PeopleImportIssueStatus(String desc) {
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
}
