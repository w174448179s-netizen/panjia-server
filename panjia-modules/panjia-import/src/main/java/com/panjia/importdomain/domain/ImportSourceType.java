package com.panjia.importdomain.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 导入数据源类型（交易业务单据）。
 * <p>
 * 存储约定：DB 存 code（code 固定取枚举名）。
 * 员工主数据导入已迁移至 people 域（panjia-people 员工导入），
 * 导入域只做交易业务单据：业绩 / 考勤 / 积分 / 费用。
 * <p>
 * 业绩单据只有唯一来源「贝壳·经纪人业绩明细表」：同一张表同时携带
 * 当月应收（新签业绩）与当月实收（结佣业绩）两列金额，由业绩引擎
 * 对同一行双发 PERF_EXPECT / PERF_REAL 两条事实，不再拆分第二导入来源。
 */
public enum ImportSourceType {

    /** 贝壳·经纪人业绩明细表（一张表同时承载应收/实收两口径） */
    KE_SIGNED("贝壳业绩明细"),

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
