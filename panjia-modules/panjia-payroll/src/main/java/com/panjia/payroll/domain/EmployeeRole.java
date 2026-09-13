package com.panjia.payroll.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/** 员工算薪角色 */
@Getter
public enum EmployeeRole {

    AGENT("经纪人"),
    MANAGER("店长"),
    DIRECTOR("总监");

    @EnumValue
    private final String code;

    EmployeeRole(String code) {
        this.code = code;
    }

    public static EmployeeRole fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (EmployeeRole r : values()) {
            if (r.name().equals(code)) {
                return r;
            }
        }
        return null;
    }
}
