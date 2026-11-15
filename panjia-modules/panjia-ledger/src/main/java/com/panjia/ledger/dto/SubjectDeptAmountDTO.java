package com.panjia.ledger.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 科目 + 部门维度金额（手工录入聚合结果）。
 */
@Data
public class SubjectDeptAmountDTO {
    private Long subjectId;
    private Long deptId;
    private BigDecimal amount;
}
