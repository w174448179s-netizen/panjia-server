package com.panjia.people.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 部门类别编码信息（店组虚拟人候选列表用）。
 */
@Data
public class DeptCategoryInfo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 部门 ID */
    private Long deptId;

    /** 部门名 */
    private String deptName;

    /** 部门类别编码（店组编码，如 CD_15_1696157；未配置为 null） */
    private String deptCategory;
}
