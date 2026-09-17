package com.panjia.people.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 修改员工请求（★一个保存按钮统一 diff，岗位为集合）。
 * <p>
 * 未变化字段不动；归属部门/岗位集合变更同步 sys_user；
 * 职级/社保开关等只走 fact；生效日可指定（默认今天）。
 * 工号不可修改（= 登录账号）。
 */
@Data
public class EmployeeUpdateDTO {

    /** 姓名 */
    private String employeeName;

    /** 归属部门 ID */
    private Long deptId;

    /** 岗位名集合（多选） */
    private List<String> postNames;

    /** 职级编码 */
    private String levelCode;

    /** 电话 */
    private String phone;

    /** 身份证号 */
    private String idCard;

    /** 报道时间 */
    private LocalDate reportDate;

    /** 入职时间 */
    private LocalDate hireDate;

    /** 离职时间（status=LEFT 时填写） */
    private LocalDate leaveDate;

    /** 状态（ACTIVE/PARTTIME/LEFT/PENDING） */
    private String status;

    /** 是否缴社保 */
    private Boolean socialInsured;

    /** 社保金额（自定义；null=不修改/用全局默认算法） */
    private BigDecimal socialFee;

    /** 是否缴公积金 */
    private Boolean housingInsured;

    /** 公积金金额（自定义；null=不修改/用全局默认） */
    private BigDecimal housingFund;

    /** 是否买商业保险 */
    private Boolean commercialInsured;

    /** 商业保险金额（自定义；null=不修改/用全局默认 21 元） */
    private BigDecimal commercialFee;

    /** 是否住宿舍 */
    private Boolean dormitory;

    /** 宿舍费金额（自定义；null=不修改/用全局默认） */
    private BigDecimal dormitoryFee;

    /** 是否兼职 */
    private Boolean parttime;

    /** 师傅工号（空串表示解除师傅关系） */
    private String mentorCode;

    /** 备注 */
    private String remark;

    /** 变更生效日（null=今天） */
    private LocalDate effectiveDate;
}
