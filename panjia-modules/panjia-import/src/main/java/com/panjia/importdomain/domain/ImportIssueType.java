package com.panjia.importdomain.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 导入问题类型（V1.4 §3.4）。
 */
public enum ImportIssueType {

    EMPLOYEE_NOT_MATCH("员工未匹配"),
    COLUMN_TYPE_ERR("列类型错误"),
    REQUIRED_MISSING("必填缺失"),
    DUPLICATE_KEY("重复键"),
    PERIOD_MISMATCH("跨月不一致"),
    DEPT_NOT_MATCH("部门路径解析失败"),
    POST_NOT_MATCH("岗位名未匹配");

    @EnumValue
    private final String code;
    private final String desc;

    ImportIssueType(String desc) {
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
