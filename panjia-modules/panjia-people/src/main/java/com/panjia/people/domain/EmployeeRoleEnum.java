package com.panjia.people.domain;

/**
 * 人员角色枚举 —— 决定算薪策略路由。
 * <p>
 * DB 字段 employee_role VARCHAR(16) 存枚举名。
 */
public enum EmployeeRoleEnum {

    /** 经纪人 */
    AGENT("经纪人"),

    /** 店长 */
    STORE_MANAGER("店长"),

    /** 总监 */
    DIRECTOR("总监");

    /** 角色描述 */
    private final String desc;

    EmployeeRoleEnum(String desc) {
        this.desc = desc;
    }

    /**
     * 获取角色描述。
     *
     * @return 描述文本
     */
    public String getDesc() {
        return desc;
    }
}
