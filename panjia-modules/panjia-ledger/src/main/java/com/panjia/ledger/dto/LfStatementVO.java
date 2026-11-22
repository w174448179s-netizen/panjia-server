package com.panjia.ledger.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 理房通流水列表行（含科目/部门名称）。
 */
@Data
public class LfStatementVO {

    private Long id;
    private String period;
    private String storeName;
    private Long storeDeptId;
    private String accountName;
    private LocalDateTime transactionTime;
    private String incomeExpenseType;
    private String transactionType;
    private BigDecimal transactionAmount;
    private BigDecimal accountBalance;
    private String businessDesc;
    private String address;
    private String houseNo;
    private String contractNo;
    private String transactionRemark;
    private String counterpartyName;
    private String bizOrderNo;
    private String voucherNo;
    private String paymentNo;
    private String category;
    private Boolean decorationFlag;
    private Long subjectId;
    private String subjectName;
    private Long expenseDeptId;
    private String expenseDeptName;
    private String allocStatus;
    private String matchStatus;
    private BigDecimal matchDiffAmount;
    private LocalDateTime matchTime;
}
