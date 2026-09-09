package com.panjia.people.dto;

import lombok.Data;

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

    /** 状态（ACTIVE/PARTTIME/LEFT/PENDING） */
    private String status;
}
