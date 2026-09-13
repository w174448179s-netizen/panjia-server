package com.panjia.payroll.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/** 调整/补发单类型 */
@Getter
public enum AdjustType {

    ADJUST("调整"),
    SUPPLEMENT("补发"),
    RECOVER("退单追回");

    @EnumValue
    private final String code;

    AdjustType(String code) {
        this.code = code;
    }
}
