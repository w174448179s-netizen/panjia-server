package com.panjia.ledger.dto;

import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelProperty;

/**
 * 部门收支录入导入/导出/模板行。
 * <p>全部字段使用 String，便于对日期、金额做宽松解析并给出逐行中文错误提示。</p>
 */
@Data
public class LedgerEntryExcelRow {

    @ExcelProperty("方向")
    private String direction;

    @ExcelProperty("科目路径")
    private String subjectPath;

    @ExcelProperty("部门")
    private String deptName;

    @ExcelProperty("金额")
    private String amount;

    @ExcelProperty("生效日期")
    private String startDate;

    @ExcelProperty("失效日期")
    private String endDate;

    @ExcelProperty("备注")
    private String remark;
}
