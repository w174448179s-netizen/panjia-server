package com.panjia.payroll.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 门店选项（顶级根的直接子部门），门店社保标准/月度配置页枚举门店用。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoreDeptVo {
    private Long deptId;
    private String deptName;
}
