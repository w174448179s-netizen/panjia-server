package com.panjia.ledger.dto;

import lombok.Data;

/**
 * 部门节点（报表列与末级部门校验使用）。
 */
@Data
public class DeptRowDTO {
    private Long deptId;
    private Long parentId;
    private String deptName;
    private Integer orderNum;
}
