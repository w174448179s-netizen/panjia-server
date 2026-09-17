package com.panjia.people.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 新增员工请求。
 * <p>
 * 基本信息 + 算薪配置一个弹窗提交；工号 = 系统账号；
 * 岗位为岗位名集合（多岗位 1:1 展开角色）。
 */
@Data
public class EmployeeCreateDTO {

    /** 工号（必填，唯一，= 系统登录账号） */
    @NotBlank(message = "工号不能为空")
    private String employeeCode;

    /** 姓名（必填） */
    @NotBlank(message = "姓名不能为空")
    private String employeeName;

    /** 归属部门 ID（必填，部门树选择） */
    @NotNull(message = "门店/组别不能为空")
    private Long deptId;

    /** 岗位名集合（必填，多选，如 ["经纪人","培训师"]） */
    @NotEmpty(message = "职位不能为空")
    private List<String> postNames;

    /** 职级编码（A0~A5/S1/S2） */
    @NotBlank(message = "职级不能为空")
    private String levelCode;

    /** 电话 */
    private String phone;

    /** 身份证号（平台加密器启用后 AES 存储） */
    private String idCard;

    /** 报道时间 */
    private LocalDate reportDate;

    /** 入职时间（必填，fact 生效日） */
    @NotNull(message = "入职时间不能为空")
    private LocalDate hireDate;

    /** 状态（ACTIVE/PARTTIME/LEFT/PENDING，默认 ACTIVE） */
    private String status;

    /** 是否缴社保 */
    private Boolean socialInsured;

    /** 社保金额（自定义；null=用全局默认算法，仅 socialInsured=true 时生效） */
    private BigDecimal socialFee;

    /** 是否缴公积金 */
    private Boolean housingInsured;

    /** 公积金金额（自定义；null=用全局默认，仅 housingInsured=true 时生效） */
    private BigDecimal housingFund;

    /** 是否买商业保险 */
    private Boolean commercialInsured;

    /** 商业保险金额（自定义；null=用全局默认 21 元，仅 commercialInsured=true 时生效） */
    private BigDecimal commercialFee;

    /** 是否住宿舍 */
    private Boolean dormitory;

    /** 宿舍费金额（自定义；null=用全局默认，仅 dormitory=true 时生效） */
    private BigDecimal dormitoryFee;

    /** 是否兼职 */
    private Boolean parttime;

    /** 师傅工号（服务端按工号解析为师傅员工 ID） */
    private String mentorCode;

    /** 备注 */
    private String remark;
}
