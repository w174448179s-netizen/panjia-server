package com.panjia.ledger.dto;

import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelProperty;

/**
 * 理房通账户明细导出文件行（14 列，全部按字符串读取后宽松解析）。
 */
@Data
public class LfStatementExcelRow {

    @ExcelProperty(value = "动账时间", index = 0)
    private String transactionTime;

    @ExcelProperty(value = "收支类型", index = 1)
    private String incomeExpenseType;

    @ExcelProperty(value = "交易类型", index = 2)
    private String transactionType;

    /** 实际表头为「交易金额（元）」，按列序号读取，兼容是否带“（元）” */
    @ExcelProperty(value = "交易金额（元）", index = 3)
    private String transactionAmount;

    /** 实际表头为「账户余额（元）」 */
    @ExcelProperty(value = "账户余额（元）", index = 4)
    private String accountBalance;

    @ExcelProperty(value = "业务描述", index = 5)
    private String businessDesc;

    @ExcelProperty(value = "地址", index = 6)
    private String address;

    @ExcelProperty(value = "房屋编号", index = 7)
    private String houseNo;

    @ExcelProperty(value = "合同编号", index = 8)
    private String contractNo;

    @ExcelProperty(value = "交易备注", index = 9)
    private String transactionRemark;

    @ExcelProperty(value = "对方户名", index = 10)
    private String counterpartyName;

    @ExcelProperty(value = "业务系统订单号", index = 11)
    private String bizOrderNo;

    @ExcelProperty(value = "记账流水号", index = 12)
    private String voucherNo;

    @ExcelProperty(value = "支付单号", index = 13)
    private String paymentNo;
}
