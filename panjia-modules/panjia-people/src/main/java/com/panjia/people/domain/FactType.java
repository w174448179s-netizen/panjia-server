package com.panjia.people.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 算薪事实类型枚举（V5.2 冻结 8 类）。
 * <p>
 * 绑定到人身上的只有开关/标签/关系：职级、状态、社保、公积金、商业保险、
 * 宿舍、兼职、师傅。社保基数/比例、公积金金额等客户级规则归 payroll 域规则 JSON。
 * <p>
 * 存储约定：DB 字段 fact_type VARCHAR(16) 存 code（code 固定取枚举名）。
 */
public enum FactType {

    /** 职级编码（A0~A5/S1/S2） */
    LEVEL("职级"),

    /** 员工状态（ACTIVE/PARTTIME/LEFT/PENDING） */
    STATUS("状态"),

    /** 是否缴社保 */
    SOCIAL("社保"),

    /** 是否缴公积金 */
    HOUSING("公积金"),

    /** 是否买商业保险 */
    COMMERCIAL("商业保险"),

    /** 是否住宿舍 */
    DORMITORY("宿舍"),

    /** 是否兼职 */
    PARTTIME("兼职"),

    /** 师傅员工 ID */
    MENTOR("师傅");

    /** 事实类型码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 事实类型中文名（变更日志展示） */
    private final String desc;

    FactType(String desc) {
        this.code = name();
        this.desc = desc;
    }

    /**
     * 获取事实类型码。
     *
     * @return 类型码
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 获取事实类型中文名。
     *
     * @return 中文名
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按 code 解析事实类型。
     *
     * @param code 类型码
     * @return 事实类型；无法识别时返回 null
     */
    public static FactType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (FactType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
