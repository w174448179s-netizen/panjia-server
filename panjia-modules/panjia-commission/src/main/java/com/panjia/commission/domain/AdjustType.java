package com.panjia.commission.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 结佣调整类型（对齐新签调整 PerformanceAdjust）。
 * <p>
 * 重构后结佣调整直接操作业绩事实（PERF_REAL + PERF_EXPECT）：
 * <ul>
 *   <li>AMOUNT：金额调整，录入调整差额，后台存调整后金额，同步调 PERF_REAL 与 PERF_EXPECT；</li>
 *   <li>VOID：业绩冲销；</li>
 *   <li>TRANSFER：部门划转。</li>
 * </ul>
 * 旧 DISCOUNT / DIFF 类型已废弃（历史数据保留展示，不再新建）。
 * <p>
 * 存储约定：DB 字段 adjust_type VARCHAR(16) 存 code（与枚举名一致）。
 */
public enum AdjustType {

    /** 金额调整：调整后金额 = target_amount（new_amount 列），差额 = diff_amount */
    AMOUNT("金额调整"),

    /** 业绩冲销 */
    VOID("业绩冲销"),

    /** 部门划转 */
    TRANSFER("部门划转");

    private final String code;
    private final String desc;

    AdjustType(String desc) {
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

    public static AdjustType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (AdjustType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
