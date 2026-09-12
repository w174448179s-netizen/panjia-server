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

    /** 状态（ApplicationStatus code） */
    private String status;
}
