package com.panjia.importdomain.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 导入数据源类型（V1.4 六类）。
 * <p>
 * 存储约定：DB 存 code（code 固定取枚举名）。
 * EMPLOYEE 是唯一不产 NormalizedRecord 的类型，走 people 域 EmployeeImportSink。
 */
public enum ImportSourceType {

    /** 贝壳·理房通到账明细（结佣） */
    KE_SIGNED("贝壳结佣"),

    /** 贝壳·新签业绩明细（应收） */
    KE_NEW_SIGN("贝壳新签"),

    /** 考勤 */
    ATTENDANCE("考勤"),

    /** 积分 */
    POINTS("积分"),

    /** 手工录入/其他费用 */
    OTHERS("手工录入"),

    /** 员工主数据（不产 NormalizedRecord，走 Sink） */
    EMPLOYEE("员工主数据");

    @EnumValue
    private final String code;
    private final String desc;

    ImportSourceType(String desc) {
        this.code = name();
        this.desc = desc;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /** 是否为业绩类（产 NormalizedRecord），EMPLOYEE 除外 */
    public boolean isPerformanceType() {
        return this != EMPLOYEE;
    }

    public static ImportSourceType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (ImportSourceType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        return null;
    }
}
