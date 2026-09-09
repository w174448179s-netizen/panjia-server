package com.panjia.people.domain;

/**
 * 人事变更类型枚举 —— 用于 pj_people_change_log.change_type 编码。
 * <p>
 * DB 字段 change_type VARCHAR(32) 存枚举名（与 DDL CHECK 约束一致），禁止魔法字符串。
 */
public enum EmployeeChangeTypeEnum {

    /** 入职建档 */
    CREATE("入职建档"),

    /** 职级变更 */
    UPDATE_LEVEL("职级变更"),

    /** 社保基数/比例变更 */
    UPDATE_SOCIAL("社保变更"),

    /** 基础档案变更 */
    UPDATE_BASE("基础档案变更"),

    /** 离职 */
    RESIGN("离职"),

    /** 转店 */
    TRANSFER("转店"),

    /** 师徒关系绑定 */
    MENTOR_CREATE("师徒关系绑定"),

    /** 师徒关系解除 */
    MENTOR_DEACTIVATE("师徒关系解除"),

    /** 兼职标记变更 */
    PART_TIME_CHANGE("兼职标记变更"),

    /** 角色变更（如经纪人→店长），影响算薪公式 */
    ROLE_CHANGE("角色变更");

    /** 变更类型描述 */
    private final String desc;

    EmployeeChangeTypeEnum(String desc) {
        this.desc = desc;
    }

    /**
     * 获取变更类型描述。
     *
     * @return 描述文本
     */
    public String getDesc() {
        return desc;
    }
}
