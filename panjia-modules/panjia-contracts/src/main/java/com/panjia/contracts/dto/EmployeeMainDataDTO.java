package com.panjia.contracts.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 员工主数据 DTO（跨域契约）。
 * <p>
 * 只含员工主数据静态字段（姓名/部门/状态/关联账户），
 * 不含算薪快照（取数日口径的 8 类事实走 PeopleQueryPort.getSnapshotAt）。
 */
@Data
@NoArgsConstructor
public class EmployeeMainDataDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 员工ID */
    private Long employeeId;

    /** 工号 */
    private String employeeCode;

    /** 姓名 */
    private String employeeName;

    /** 部门ID（sys_dept） */
    private Long deptId;

    /** 部门全路径展示名（如 "云庭店/花照云庭店A组"） */
    private String deptName;

    /** 员工状态 */
    private String status;

    /** 关联系统用户ID（sys_user） */
    private Long userId;
}
