package com.panjia.ledger.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 部门收支分析报表。
 */
@Data
public class LedgerReportVO {

    private String period;

    /** 顶层部门名称（多根时拼接） */
    private String rootDeptName;

    private List<LedgerReportColumnVO> columns;

    private List<LedgerReportRowVO> rows;

    /** income / expense / profit 全公司合计 */
    private BigDecimal incomeTotal;
    private BigDecimal expenseTotal;
    private BigDecimal profitTotal;
}
