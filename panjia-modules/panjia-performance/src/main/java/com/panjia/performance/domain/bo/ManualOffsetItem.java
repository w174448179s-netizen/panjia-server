package com.panjia.performance.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 业绩冲正/补录单条明细。
 */
@Data
public class ManualOffsetItem {
    @NotNull(message = "员工不能为空")
    private Long employeeId;
    @NotBlank(message = "角色类型不能为空")
    private String roleType;
    private String roleName;
    @NotNull(message = "金额不能为空")
    private BigDecimal amount;
}
