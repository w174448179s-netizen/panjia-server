package com.panjia.payroll.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/** 手工录入项类型 */
@Getter
public enum ManualItemType {

    BONUS("奖金"),
    OTHER_INCOME("其他收入"),
    OTHER_DEDUCT("其他支出");

    @EnumValue
    private final String code;

    ManualItemType(String code) {
        this.code = code;
    }

    public static ManualItemType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ManualItemType t : values()) {
            if (t.name().equals(code)) {
                return t;
            }
        }
        return null;
    }

    public boolean isIncome() {
        return this != OTHER_DEDUCT;
    }
}
