package com.panjia.commission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 发起结佣调整单请求 DTO（DISCOUNT / DIFF / VOID，结佣域详细设计 §4.5）。
 */
@Data
public class AdjustCreateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 调整对象申请单 ID */
    @NotNull(message = "申请单不能为空")
    private Long applicationId;

    /** 调整对象结佣明细 ID */
    @NotNull(message = "结佣明细不能为空")
    private Long itemId;

    /** 调整类型（DISCOUNT / DIFF / VOID） */
    @NotBlank(message = "调整类型不能为空")
    private String adjustType;

    /** 折后最终金额（DISCOUNT 用，必填，直接存折后值，非系数） */
    private BigDecimal newAmount;

    /** 差额金额（DIFF 用，必填，正补负扣） */
    private BigDecimal diffAmount;

    /** 补发目标月（DIFF 用，必填 YYYY-MM；封账校验按此月判定） */
    private String targetPeriod;

    /** 调整原因（必填，审计；折扣种类如 85 折写此处） */
    @NotBlank(message = "调整原因不能为空")
    private String reason;
}
