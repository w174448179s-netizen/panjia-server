package com.panjia.commission.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 发起结佣调整单请求 DTO（对齐新签调整：直接操作业绩事实 PERF_REAL + PERF_EXPECT）。
 */
@Data
public class CommissionAdjustCreateBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 调整对象申请单 ID */
    @NotNull(message = "申请单不能为空")
    private Long applicationId;

    /** 调整对象结佣明细 ID（明细级必填，合同级为空） */
    private Long itemId;

    /** 调整范围：CONTRACT-合同级 / DETAIL-明细级 */
    @NotBlank(message = "调整范围不能为空")
    private String adjustScope;

    /** 调整类型（AMOUNT / VOID / TRANSFER） */
    @NotBlank(message = "调整类型不能为空")
    private String adjustType;

    /** 调整后金额（AMOUNT 用，前端 = 当前金额 + 录入差额） */
    private BigDecimal targetAmount;

    /** 部门划转目标部门 ID（TRANSFER 用） */
    private Long targetDeptId;

    /** 调整原因（必填，审计） */
    @NotBlank(message = "调整原因不能为空")
    private String reason;
}
