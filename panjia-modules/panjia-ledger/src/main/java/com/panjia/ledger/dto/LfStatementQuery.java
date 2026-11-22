package com.panjia.ledger.dto;

import lombok.Data;

/**
 * 理房通流水查询条件。
 */
@Data
public class LfStatementQuery {

    private String period;
    private Long storeDeptId;
    private Long accountId;
    private String incomeExpenseType;
    private String transactionType;
    private String category;
    /** 是否去化（售前房屋美化）流水 */
    private Boolean decorationFlag;
    private String allocStatus;
    private String matchStatus;
    private String businessDesc;
    private String transactionRemark;
    private String contractNo;
    private String keyword;
}
