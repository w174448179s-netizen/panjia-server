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
}
