package com.panjia.people.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 部门树节点（门店/组别树选择用）。
 */
@Data
public class DeptNode {

    /** 部门 ID */
    private Long deptId;

    /** 部门名 */
    private String deptName;

    /** 父部门 ID */
    private Long parentId;

    /** 子部门 */
    private List<DeptNode> children = new ArrayList<>();
}
