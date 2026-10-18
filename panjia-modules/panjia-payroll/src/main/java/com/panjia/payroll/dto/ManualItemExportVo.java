package com.panjia.payroll.dto;

import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelProperty;

/**
 * 其他收支/奖金 导出行 VO（ExcelBuilder 导出用）
 */
@Data
public class ManualItemExportVo {

    @ExcelProperty("员工编码")
    private String employeeCode;

    @ExcelProperty("员工姓名")
    private String employeeName;

    @ExcelProperty("类型")
    private String itemTypeLabel;

    @ExcelProperty("明细类型")
    private String subType;

    @ExcelProperty("金额")
    private java.math.BigDecimal amount;

    @ExcelProperty("生效起始月")
    private String startMonth;

    @ExcelProperty("生效截止月")
    private String endMonth;

    @ExcelProperty("事由")
    private String reason;

    @ExcelProperty("创建时间")
    private String createTime;
}
