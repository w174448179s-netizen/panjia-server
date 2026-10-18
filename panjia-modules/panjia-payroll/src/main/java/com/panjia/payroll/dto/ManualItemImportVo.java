package com.panjia.payroll.dto;

import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.math.BigDecimal;

/**
 * 其他收支/奖金 导入行 VO（ExcelBuilder 读取用；员工编码必填，截止月留空=长期生效）
 */
@Data
public class ManualItemImportVo {

    @ExcelProperty("员工编码")
    private String employeeCode;

    @ExcelProperty("员工姓名")
    private String employeeName;

    @ExcelProperty("类型")
    private String itemTypeLabel;

    @ExcelProperty("金额")
    private BigDecimal amount;

    @ExcelProperty("生效起始月")
    private String startMonth;

    @ExcelProperty("生效截止月")
    private String endMonth;

    @ExcelProperty("事由")
    private String reason;
}
