package com.panjia.commission.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

/**
 * 结佣明细查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class ItemQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业绩归属月（YYYY-MM） */
    private String period;

    /** 员工 ID */
    private Long employeeId;

    /** 门店 ID */
    private Long deptId;

    /** 状态（ItemStatus code） */
    private String status;

    /** 所属申请单 ID */
    private Long applicationId;
}
