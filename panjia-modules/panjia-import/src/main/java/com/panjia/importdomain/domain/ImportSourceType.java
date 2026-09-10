package com.panjia.importdomain.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 导入数据源类型（V2.0 五类交易业务单据）。
 * <p>
 * 存储约定：DB 存 code（code 固定取枚举名）。
 * 员工主数据导入已迁移至 people 域（panjia-people 员工导入），
 * 导入域只做交易业务单据：业绩 / 考勤 / 积分 / 费用。
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
    OTHERS("手工录入");

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
