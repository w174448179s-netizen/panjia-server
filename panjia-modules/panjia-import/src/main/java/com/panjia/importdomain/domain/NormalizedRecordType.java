package com.panjia.importdomain.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 归一化记录类型（交易单据：业绩/考勤/积分/手工费用）。
 * <p>
 * 贝壳业绩（SIGNED）同一行双发双口径事实；历史工资单口径行（HIST_EXPECT/HIST_REAL）
 * 单发 PERF_EXPECT / PERF_REAL——空列按 0 处理的 SIGNED 双口径语义不适用于历史行
 * （会造出 0 金额事实），故独立成两个类型。
 */
public enum NormalizedRecordType {

    SIGNED("业绩明细"),
    ATTENDANCE("考勤"),
    POINTS("积分"),
    MANUAL("手工"),

    /** 历史工资·新签业绩（单发 PERF_EXPECT，金额=85后÷折算因子） */
    HIST_EXPECT("历史新签业绩"),

    /** 历史工资·结佣业绩（单发 PERF_REAL，金额=85后÷折算因子） */
    HIST_REAL("历史结佣业绩"),

    /** 历史工资·工资族行（工资表/总监/店长/人事补丁/绩效扣款左右半，全字段进 extraJson） */
    PAYROLL_WAGE("历史工资");

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
