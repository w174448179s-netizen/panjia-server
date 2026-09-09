package com.panjia.people.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 员工创建 DTO。
 * <p>
 * 角色 / 兼职状态以字符串接收（枚举名），由 Converter 转枚举。
 */
@Data
public class EmployeeCreateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 关联 sys_user.id（可空，无登录账号员工为 null） */
    private Long userId;

    /** 工号（业务唯一标识） */
    @NotBlank(message = "工号不能为空")
    @Size(max = 64, message = "工号长度不能超过 64")
    private String employeeCode;

    /** 姓名 */
    @NotBlank(message = "姓名不能为空")
    @Size(max = 64, message = "姓名长度不能超过 64")
    private String name;

    /** 手机号 */
    @Size(max = 20, message = "手机号长度不能超过 20")
    private String phone;

    /** 身份证号（加密存储） */
    @Size(max = 64, message = "身份证号长度不能超过 64")
    private String idCardNo;

    /** 所属门店/部门 ID */
    @NotNull(message = "所属部门不能为空")
    private Long deptId;

    /** 岗位 ID（可空） */
    private Long postId;

    /** 人员角色：AGENT / STORE_MANAGER / DIRECTOR */
    @NotBlank(message = "人员角色不能为空")
    @Pattern(regexp = "AGENT|STORE_MANAGER|DIRECTOR", message = "人员角色必须为 AGENT/STORE_MANAGER/DIRECTOR")
    private String role;

    /** 兼职状态：FULL_TIME / PART_TIME */
    @Pattern(regexp = "FULL_TIME|PART_TIME", message = "兼职状态必须为 FULL_TIME/PART_TIME")
    private String partTimeStatus;

    /** 入职日期 */
    @NotNull(message = "入职日期不能为空")
    private LocalDate hireDate;

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

    /** 初始职级编码（如 A0 / S1） */
    @NotBlank(message = "初始职级编码不能为空")
    private String levelCode;

    /** 社保个人承担比例（如 0.30） */
    private BigDecimal socialInsuranceRatio;
}
