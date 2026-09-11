package com.panjia.importdomain.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 导入问题来源阶段。
 * <p>
 * 存储约定：DB 存 code（code 固定取枚举名）。
 * {@code doNormalizePhase} 清理旧数据时只删 {@link #NORMALIZE}，
 * {@link #PARSE} 阶段的基础校验产物（REQUIRED_MISSING 等）必须保留。
 */
public enum ImportIssuePhase {

    /** 文件解析 + 基础校验（模板 required / validation_rules / 类型转换）阶段产生 */
    PARSE("解析/基础校验"),

    /** 归一化（员工匹配等）阶段产生 */
    NORMALIZE("归一化");

    @EnumValue
    private final String code;
    private final String desc;

    ImportIssuePhase(String desc) {
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
}
