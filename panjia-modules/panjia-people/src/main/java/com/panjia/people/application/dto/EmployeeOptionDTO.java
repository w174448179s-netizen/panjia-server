package com.panjia.people.application.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 员工轻量选项 DTO（用于下拉搜索）。
 */
@Data
public class EmployeeOptionDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 员工 ID */
    private Long id;

    /** 工号 */
    private String employeeCode;

    /** 姓名 */
    private String name;
}
