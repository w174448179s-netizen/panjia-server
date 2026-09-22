package com.panjia.commission.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

/**
 * 结佣调整单查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class AdjustQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业绩归属月（YYYY-MM） */
    private String period;

    /** 申请单 ID */
    private Long applicationId;

    /** 调整类型（AdjustType code） */
    private String adjustType;

    /** 状态（AdjustStatus code） */
    private String status;

    /** 员工 ID */
    private Long employeeId;

    /** 部门 ID */
    private Long deptId;

    /** 业务类型 */
    private String bizType;

    /** 关键字（合同号 / 订单号 / 物业地址） */
    private String keyword;
}
