package com.panjia.people.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 员工导入校验通过行（导入域 Normalizer 产出，EmployeeImportSink 入参）。
 * <p>
 * 字段对齐 V5.2 导入模板：岗位名以 "/" 分隔解析为 {@link #postNames} 集合，
 * 师傅以工号传入，由 people 域解析为师傅员工 ID。
 */
@Data
public class ValidatedEmployeeRow {

    /** 部门全路径 "门店-组别" */
    private String deptFull;

    /** 工号（唯一，= 系统账号） */
    private String employeeCode;

    /** 姓名 */
    private String employeeName;

    /** 岗位名集合（多岗位） */
    private List<String> postNames;

    /** 职级编码 */
    private String levelCode;

    /** 电话 */
    private String phone;

    /** 身份证号 */
    private String idCard;

    /** 报道时间 */
    private LocalDate reportDate;

    /** 入职时间（fact 生效日） */
    private LocalDate hireDate;

    /** 是否缴社保 */
    private Boolean socialInsured;

    /** 是否缴公积金 */
    private Boolean housingInsured;

    /** 是否买商业保险 */
    private Boolean commercialInsured;

    /** 是否住宿舍 */
    private Boolean dormitory;

    /** 是否兼职 */
    private Boolean parttime;

    /** 师傅工号（可空） */
    private String mentorCode;
}
