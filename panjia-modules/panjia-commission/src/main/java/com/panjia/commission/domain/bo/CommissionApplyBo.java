package com.panjia.commission.domain.bo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

/**
 * 结佣申请单查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class CommissionApplyBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业绩归属月（YYYY-MM） */
    private String period;

    /** 门店 ID */
    private Long deptId;

    /** 状态（ApplicationStatus code：DRAFT/SUBMITTED/LOCKED/REJECTED/CANCELLED） */
    private String status;

    /** 关键字（合同号 / 订单号 / 房源地址） */
    private String keyword;

    /** 员工 ID */
    private Long employeeId;

    /** 业务类型 */
    private String bizType;

    /** 当前审批节点（DIRECTOR/FINANCE） */
    private String currentNode;

    /**
     * 退单三态筛选（合同级已退单口径）。
     * <ul>
     *   <li>REFUND=仅退单（存在已执行退单）；</li>
     *   <li>NORMAL=仅正常（排除已退单）；</li>
     *   <li>空=全部。</li>
     * </ul>
     */
    private String refundFilter;
}
