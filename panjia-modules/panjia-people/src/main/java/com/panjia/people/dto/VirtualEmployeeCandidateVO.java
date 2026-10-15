package com.panjia.people.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 店组虚拟人候选行（一键新建弹窗列表）。
 */
@Data
public class VirtualEmployeeCandidateVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 部门 ID */
    private Long deptId;

    /** 部门全路径名（跳过客户根节点），如 "天街/西派少城店A组" */
    private String deptFullName;

    /** 店组编码（sys_dept.dept_category；未配置为 null） */
    private String deptCategory;

    /** 该部门下是否已有虚拟人（工号 = 店组编码） */
    private Boolean hasVirtual;

    /** 已有虚拟人的员工 ID（无虚拟人为 null） */
    private Long virtualEmployeeId;
}
