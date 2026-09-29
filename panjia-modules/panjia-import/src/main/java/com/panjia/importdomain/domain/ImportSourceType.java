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
 * 业绩单据两来源：贝壳新签导入（KE_SIGNED，经纪人业绩结算明细表，承载应收口径）
 * 与贝壳实收导入（KE_RECEIVED，理房通到账贡献明细表，承载实收口径），
 * 由业绩引擎分别单发 PERF_EXPECT / PERF_REAL 事实。
 */
public enum ImportSourceType {

    /** 贝壳新签导入·经纪人业绩结算明细表（原「贝壳业绩导入」，只发应收口径） */
    KE_SIGNED("贝壳新签明细"),

    /** 贝壳实收导入·理房通到账贡献明细表（角色人当月到账金额，可为负） */
    KE_RECEIVED("贝壳实收明细"),

    /** 考勤 */
    ATTENDANCE("考勤"),

    /** 积分 */
    POINTS("积分"),

    /** 手工录入/其他费用 */
    OTHERS("手工录入"),

    /**
     * 历史工资 Excel（天街工资表 7 个 sheet）。
     * 走标准管线多模板变体：同 sourceType 共存多套激活模板（HIST_MULTI_V1），
     * 一模板一 sheet 循环解析共建一个批次，RawData 按运行时类型分流 4 张 raw 表；
     * 归档后各域（performance/people/payroll/commission）消费自己的归一化数据。
     */
    HISTORY_PAYROLL("历史工资");

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
