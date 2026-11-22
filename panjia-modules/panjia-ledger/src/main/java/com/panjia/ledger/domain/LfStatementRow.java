package com.panjia.ledger.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 理房通对公账户流水行。
 */
@Data
@TableName("pj_lf_statement_row")
public class LfStatementRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long batchId;
    private Long accountId;
    private Long storeDeptId;
    private String storeName;

    /** 归属月 YYYY-MM */
    private String period;

    private LocalDateTime transactionTime;

    /** 收入 / 支出 / 提现 */
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
    private Integer rawRowNo;

    /** BUSINESS/REVERSAL/FEE/DEPT_INCOME/WITHDRAW/NON_BUSINESS */
    private String category;

    /** 售前房屋美化（去化模式） */
    private Boolean decorationFlag;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long subjectId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long expenseDeptId;

    /** AUTO/PENDING/MANUAL */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String allocStatus;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long allocBy;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime allocTime;

    /** MATCHED/TOLERANCE_MATCH/AMOUNT_DIFF/NOT_FOUND/MULTI_MATCH/PENDING_SIGN_ROLE */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String matchStatus;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal matchDiffAmount;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long receivedContractId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime matchTime;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
