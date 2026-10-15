package com.panjia.people.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 一键新建店组虚拟人请求项。
 */
@Data
public class VirtualEmployeeInitDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 部门 ID */
    @NotNull(message = "部门 ID 不能为空")
    private Long deptId;

    /** 店组编码（写入 sys_dept.dept_category，同时作为虚拟人工号） */
    @NotBlank(message = "店组编码不能为空")
    private String deptCategory;
}
