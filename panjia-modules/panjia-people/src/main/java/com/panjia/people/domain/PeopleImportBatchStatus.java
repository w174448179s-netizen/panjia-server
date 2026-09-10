package com.panjia.people.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 员工导入批次状态（V6.0 §5.3，people 域独立状态机）。
 * <p>
 * 存储约定：DB 字段 status VARCHAR(16) 存 code（code 固定取枚举名），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 * FAILED 为终态，只能新建批次重试，禁止回退。
 */
public enum PeopleImportBatchStatus {

    /** 解析中（工具层解析 + 落 raw） */
    PARSING("解析中"),

    /** 业务校验中（工号唯一/部门路径/师傅/职级） */
    VALIDATING("校验中"),

    /** 单一大原子事务落地中 */
    IMPORTING("导入中"),

    /** 导入成功 */
    SUCCESS("成功"),

    /** 终态失败（只能新建批次重试） */
    FAILED("失败");

    /** 状态码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 状态描述 */
    private final String desc;

    PeopleImportBatchStatus(String desc) {
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
    public static PeopleImportBatchStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (PeopleImportBatchStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
