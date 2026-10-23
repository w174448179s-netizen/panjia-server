package com.panjia.performance.domain.bo;

import lombok.Data;

/**
 * 退单列表查询条件（pj_perf_refund）。
 */
@Data
public class PerfRefundBo {

    /** 退单落账期间 YYYY-MM */
    private String refundPeriod;

    /** 状态 code（SUBMITTED/APPROVED/REJECTED/CANCELLED/EXECUTED） */
    private String status;

    /** 部门 ID（数据权限） */
    private Long deptId;

    /** 员工 ID */
    private Long employeeId;

    /** 业务类型 */
    private String bizType;

    /** 关键字（退单号 / 合同号 / 订单号） */
    private String keyword;
}
