package com.panjia.people.application.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 员工档案前端返回 DTO（不含身份证号等敏感信息）。
 */
@Data
public class EmployeeDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 员工 ID */
    private Long id;

    /** 关联 sys_user.id */
    private Long userId;

    /** 工号 */
    private String employeeCode;

    /** 姓名 */
    private String name;

    /** 手机号 */
    private String phone;

    /** 所属门店/部门 ID */
    private Long deptId;

    /** 岗位 ID */
    private Long postId;

    /** 人员角色（枚举名） */
    private String role;

    /** 兼职状态（枚举名） */
    private String partTimeStatus;

    /** 员工状态（枚举名） */
    private String status;

    /** 入职日期 */
    private LocalDate hireDate;

    /** 离职日期 */
    private LocalDate resignDate;

    /** 是否缴纳社保 */
    private Boolean socialInsuranceEnabled;

    /** 公积金自缴金额 */
    private BigDecimal housingFundAmount;

    /** 是否购买商业保险 */
    private Boolean commercialInsurance;

    /** 是否住宿舍 */
    private Boolean dormitoryEnabled;

    /** 备注 */
    private String remark;

    /** 当前职级编码（由职级历史装配） */
    private String currentLevelCode;
}
