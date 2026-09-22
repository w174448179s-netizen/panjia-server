package com.panjia.performance.domain.bo;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 业绩查询业务类型下拉选项查询条件。
 */
@Data
@NoArgsConstructor
public class PerformanceSearchBizTypesBo {

    /** 归属期间（可选） */
    private String period;

    /** 部门 ID（可选，含子部门） */
    private Long deptId;

    /** 员工 ID（可选） */
    private Long employeeId;
}
