package com.panjia.people.application.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 员工基础档案更新 DTO（不含职级变更，职级走 EmployeeLevelService）。
 */
@Data
public class EmployeeUpdateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 姓名 */
    private String name;

    /** 手机号 */
    private String phone;

    /** 所属门店/部门 ID */
    private Long deptId;

    /** 岗位 ID */
    private Long postId;

    /** 人员角色：AGENT / STORE_MANAGER / DIRECTOR */
    @Pattern(regexp = "AGENT|STORE_MANAGER|DIRECTOR", message = "人员角色必须为 AGENT/STORE_MANAGER/DIRECTOR")
    private String role;

    /** 兼职状态：FULL_TIME / PART_TIME */
    @Pattern(regexp = "FULL_TIME|PART_TIME", message = "兼职状态必须为 FULL_TIME/PART_TIME")
    private String partTimeStatus;

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

    /** 变更原因 */
    private String reason;
}
