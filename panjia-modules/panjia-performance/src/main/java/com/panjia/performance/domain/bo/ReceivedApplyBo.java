package com.panjia.performance.domain.bo;

import lombok.Data;

/**
 * 实收业绩审批单列表查询条件。
 */
@Data
public class ReceivedApplyBo {

    /** 结算月 YYYY-MM */
    private String period;

    /**
     * 状态（SUBMITTED/APPROVED/REJECTED/CANCELLED），另支持列表专用的 {@code PENDING}：
     * 只查「待匹配新签」到账行（无审批单、仅有未绑定 ACTIVE 明细），不参与审批口径。
     */
    private String status;

    /** 当前节点（FINANCE/DIRECTOR） */
    private String currentNode;

    /** 关键字（合同号/订单号/物业地址） */
    private String keyword;

    /** 批次 ID */
    private Long batchId;

    /** 部门 ID（店长仅能查本店，服务端强制注入） */
    private Long deptId;

    /** 员工 ID */
    private Long employeeId;

    /** 业务类型 */
    private String bizType;

    /**
     * 退单三态筛选。
     * <ul>
     *   <li>REFUND=仅退单（单据为 TD 冲减单，apply_no 以 'TD' 开头；合并行按合同是否存在 TD 冲减单过滤）；</li>
     *   <li>NORMAL=仅正常（排除 TD 冲减单）；</li>
     *   <li>空=全部。</li>
     * </ul>
     */
    private String refundFilter;
}
