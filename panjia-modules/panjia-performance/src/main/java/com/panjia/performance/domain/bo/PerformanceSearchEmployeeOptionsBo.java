package com.panjia.performance.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 业绩查询员工下拉选项查询条件。
 */
@Data
@NoArgsConstructor
public class PerformanceSearchEmployeeOptionsBo {

    /** 姓名或工号关键字 */
    @NotBlank(message = "关键字不能为空")
    private String keyword;

    /** 部门 ID（可选，含子部门） */
    private Long deptId;
}
