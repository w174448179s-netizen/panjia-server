package com.panjia.people.domain;

/**
 * 员工状态枚举（含状态机方法）。
 * <p>
 * DB 字段 status VARCHAR(16) 存枚举名；状态流转：
 * ACTIVE ⇄ ON_LEAVE，ACTIVE/ON_LEAVE → RESIGNED（终态）。
 */
public enum EmployeeStatusEnum {

    /** 在职 */
    ACTIVE("在职"),

    /** 离职（终态） */
    RESIGNED("离职"),

    /** 停薪留职 */
    ON_LEAVE("停薪留职");

    /** 状态描述 */
    private final String desc;

    EmployeeStatusEnum(String desc) {
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

    /**
     * 判断是否可流转到目标状态。
     *
     * @param target 目标状态
     * @return true 表示允许流转
     */
    public boolean canTransitTo(EmployeeStatusEnum target) {
        if (this == target) {
            return false;
        }
        switch (this) {
            case ACTIVE:
                return target == RESIGNED || target == ON_LEAVE;
            case ON_LEAVE:
                return target == ACTIVE || target == RESIGNED;
            case RESIGNED:
                return false;
            default:
                return false;
        }
    }

    /**
     * 是否终态。
     *
     * @return true 表示已离职（终态，不可再流转）
     */
    public boolean isTerminal() {
        return this == RESIGNED;
    }

    /**
     * 是否在职。
     *
     * @return true 表示在职
     */
    public boolean isActive() {
        return this == ACTIVE;
    }
}
