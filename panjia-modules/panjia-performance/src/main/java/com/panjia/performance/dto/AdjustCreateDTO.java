package com.panjia.performance.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 业绩调整单创建请求。
 */
@Data
@NoArgsConstructor
public class AdjustCreateDTO {

    /** 关联业绩事实 ID */
    private Long factId;

    /** 归属期间 YYYY-MM */
    private String period;

    /** 员工 ID */
    private Long employeeId;

    /** 原部门 ID */
    private Long deptId;

    /** 调整类型：AMOUNT / VOID / TRANSFER */
    private String adjustType;

    /** 调整范围：CONTRACT-合同级 / DETAIL-明细级 */
    private String adjustScope;

    /** 合同号（合同级调整时必填，用于定位该合同下全部明细） */
    private String contractNo;

    /** 事实口径：仅允许 PERF_EXPECT（§4.1 业绩调整只改应收） */
    private String factType;

    /**
     * 原业绩归属月 YYYY-MM（合同级跨月调整时必填，用于定位原月事实；
     * 为空则视 period 为原月，即同月调整）。§4.6 跨月调整走业绩冲销。
     */
    private String originalPeriod;

    /** 金额变动值（金额调整时使用） */
    private BigDecimal deltaAmount;

    /** 目标部门 ID（部门划转时使用） */
    private Long targetDeptId;

    /** 调整原因 */
    private String reason;

    /** 调整详情 JSON（扩展字段） */
    private String payloadJson;
}
