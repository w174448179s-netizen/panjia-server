package com.panjia.importdomain.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 归一化记录类型（交易单据：业绩/考勤/积分/手工费用）。
 * <p>
 * 业绩记录只有 SIGNED 一种：来源唯一（贝壳·经纪人业绩明细表），
 * 同一行业绩同时携当月应收 + 当月实收，由业绩引擎双发双口径事实。
 */
public enum NormalizedRecordType {

    SIGNED("业绩明细"),
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
