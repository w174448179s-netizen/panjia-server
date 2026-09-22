package com.panjia.performance.domain.bo;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 业绩管理合同维度分页查询条件（懒加载树表：合同 → 人 → 明细）。
 */
@Data
@NoArgsConstructor
public class PerformanceManageContractBo {

    /** 归属期间 YYYY-MM */
    private String period;

    /** 事实口径：PERF_REAL / PERF_EXPECT */
    private String factType;

    /** 部门 ID（含子部门） */
    private Long deptId;

    /** 员工 ID */
    private Long employeeId;

    /** 业务类型 */
    private String bizType;

    /** 关键字（合同号/订单号/物业地址/角色） */
    private String keyword;

    /** 事实状态 */
    private String factStatus;
}
