package com.panjia.commission.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

/**
 * 结佣申请单查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class ApplyQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业绩归属月（YYYY-MM） */
    private String period;

    /** 门店 ID */
    private Long deptId;

    /** 状态（ApplicationStatus code；NONE=未发起） */
    private String status;

    /** 关键字（合同号 / 订单号 / 房源地址） */
    private String keyword;

    /** 当前审批节点（DIRECTOR/FINANCE） */
    private String currentNode;
}
