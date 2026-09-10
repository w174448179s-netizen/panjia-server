package com.panjia.importdomain.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 归一化记录类型（五类交易单据：结佣/新签/考勤/积分/手工费用）。
 */
public enum NormalizedRecordType {

    SIGNED("结佣"),
    NEW_SIGN("新签"),
    ATTENDANCE("考勤"),
    POINTS("积分"),
    MANUAL("手工");

    @EnumValue
    private final String code;
    private final String desc;

    NormalizedRecordType(String desc) {
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

    public static NormalizedRecordType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (NormalizedRecordType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        return null;
    }
}
