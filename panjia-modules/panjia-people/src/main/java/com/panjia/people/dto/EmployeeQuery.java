package com.panjia.people.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 员工列表筛选条件。
 */
@Data
public class EmployeeQuery {

    /** 工号（模糊） */
    private String employeeCode;

    /** 姓名（模糊） */
    private String employeeName;

    /** 部门 ID（含下级部门） */
    private Long deptId;

    /** 岗位名（精确匹配，筛选拥有该岗位的员工） */
    private String postName;

    /** 状态集合（ACTIVE/PARTTIME/LEFT/PENDING），支持多选；为空表示不限制 */
    private List<String> status;

    /** 性别（MALE/FEMALE，精确；为空表示不限制） */
    private String gender;

    /** 入职时间范围-起（含，按 hire_date） */
    private LocalDate hireDateBegin;

    /** 入职时间范围-止（含，按 hire_date） */
    private LocalDate hireDateEnd;

    /** 排序字段（createTime=默认录入时间倒序；hireDate=入职时间） */
    private String sortField;

    /** 排序方向（asc/desc；默认 desc） */
    private String sortOrder;
}
