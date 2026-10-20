package com.panjia.people.dto;

import lombok.Data;

/**
 * 员工合同列表筛选条件。
 */
@Data
public class ContractQuery {

    /** 员工 ID（精确） */
    private Long employeeId;

    /** 工号（模糊） */
    private String employeeCode;

    /** 姓名（模糊） */
    private String employeeName;

    /** 合同类型（LABOR/PARTTIME/OTHER） */
    private String contractType;

    /** 签约门店（含下级部门） */
    private Long deptId;
}
