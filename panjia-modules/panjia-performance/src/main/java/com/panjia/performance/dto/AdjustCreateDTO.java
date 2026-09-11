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

    /** 金额变动值（金额调整时使用） */
    private BigDecimal deltaAmount;

    /** 目标部门 ID（部门划转时使用） */
    private Long targetDeptId;

    /** 调整原因 */
    private String reason;

    /** 调整详情 JSON（扩展字段） */
    private String payloadJson;
}
