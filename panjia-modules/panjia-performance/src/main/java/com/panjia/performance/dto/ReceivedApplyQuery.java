package com.panjia.performance.dto;

import lombok.Data;

/**
 * 实收业绩审批单列表查询条件。
 */
@Data
public class ReceivedApplyQuery {

    /** 结算月 YYYY-MM */
    private String period;

    /** 状态（DRAFT/SUBMITTED/APPROVED/REJECTED/CANCELLED） */
    private String status;

    /** 当前节点（FINANCE/DIRECTOR） */
    private String currentNode;

    /** 关键字（合同号/订单号/物业地址） */
    private String keyword;

    /** 批次 ID */
    private Long batchId;

    /** 部门 ID（店长仅能查本店，服务端强制注入） */
    private Long deptId;
}
