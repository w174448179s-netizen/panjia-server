package com.panjia.contracts.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 员工-渠道账号映射（工号 ↔ 渠道 userId）。
 * <p>
 * 钉钉通讯录 {@code /topapi/v2/user/list} 不返回已离职员工，但离职当月的
 * 历史考勤报表 / 积分日志仍可按其 userId 拉取。本 DTO 承载本地映射表中
 * 离职员工的（工号, 渠道 userId），供同步模块把离职员工补进拉取名单。
 */
@Data
public class EmployeeChannelMappingDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 工号（pj_people_employee.employee_code） */
    private String employeeCode;

    /** 渠道内用户唯一标识（钉钉 userid 等） */
    private String externalUserId;
}
