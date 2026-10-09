package com.panjia.performance.domain.bo;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 完整业绩查询（合同维度）条件。
 */
@Data
@NoArgsConstructor
public class PerformanceSearchBo {

    /** 归属期间（可选，为空时查全部期间） */
    private String period;

    /** 部门 ID（可选，含子部门） */
    private Long deptId;

    /** 业务类型（可选，精确匹配） */
    private String bizType;

    /** 关键字（可选：合同号/订单号/物业地址） */
    private String keyword;

    /** 员工 ID（可选：员工筛选） */
    private Long employeeId;

    /**
     * 是否结佣（可选：结佣状态筛选）。
     * <ul>
     *   <li>true=已结佣（存在 status IN APPROVED/LOCKED/CLOSED 的结佣申请单）；</li>
     *   <li>false=未结佣；</li>
     *   <li>null=不过滤。</li>
     * </ul>
     */
    private Boolean settled;
}
