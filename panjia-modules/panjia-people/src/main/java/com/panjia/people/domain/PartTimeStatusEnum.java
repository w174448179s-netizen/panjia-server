package com.panjia.people.domain;

/**
 * 兼职状态枚举。
 * <p>
 * 独立字段，与底薪逻辑无关：FULL_TIME 但 baseSalary=0 是合法的（如 A1 经纪人）。
 * DB 字段 part_time_status VARCHAR(16) 存枚举名。
 */
public enum PartTimeStatusEnum {

    /** 全职 */
    FULL_TIME("全职"),

    /** 兼职 */
    PART_TIME("兼职");

    /** 状态描述 */
    private final String desc;

    PartTimeStatusEnum(String desc) {
        this.desc = desc;
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
