package com.panjia.license.enums;

/**
 * 关键操作枚举。
 * /check 接口按操作粒度返回允许/拒绝，支持精细控制。
 * 示例：仅限制算薪，允许导出。
 */
public enum OperationEnum {

    /** 导入结佣数据 */
    IMPORT("导入结佣数据"),

    /** 生成算薪 */
    CALCULATE("生成算薪"),

    /** 导出 Excel */
    EXPORT("导出 Excel");

    private final String description;

    OperationEnum(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
