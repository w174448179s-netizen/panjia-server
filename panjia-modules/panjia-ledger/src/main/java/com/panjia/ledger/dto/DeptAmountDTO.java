package com.panjia.ledger.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 部门维度金额（实收明细 / 工资应发聚合结果）。
 */
@Data
public class DeptAmountDTO {
    private Long deptId;
    private BigDecimal amount;
}
