package com.panjia.performance.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 员工快照 DTO。
 * <p>
 * 用于跨域读取 people 域员工快照信息，包含员工基本信息与所属部门。
 */
@Data
@NoArgsConstructor
public class EmployeeSnapshotDTO {

    /** 员工ID */
    private Long employeeId;

    /** 工号 */
    private String employeeCode;

    /** 姓名 */
    private String employeeName;

    /** 部门ID */
    private Long deptId;

    /** 部门名称 */
    private String deptName;

    /** 员工状态 */
    private String status;

    /** 关联用户ID */
    private Long userId;
}
