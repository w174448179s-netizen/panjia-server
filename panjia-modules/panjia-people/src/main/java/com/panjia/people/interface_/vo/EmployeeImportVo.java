package com.panjia.people.interface_.vo;

import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.dromara.common.excel.annotation.ExcelDictFormat;
import org.dromara.common.excel.convert.ExcelDictConvert;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 员工导入 Excel VO。
 * <p>
 * 客户在 Excel 中填写名称（角色/职级/兼职状态用字典 label），由 ExcelDictConvert 自动转 code。
 */
@Data
public class EmployeeImportVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @ExcelProperty(value = "工号")
    private String employeeCode;

    @ExcelProperty(value = "姓名")
    private String name;

    @ExcelProperty(value = "手机号")
    private String phone;

    @ExcelProperty(value = "身份证号")
    private String idCardNo;

    @ExcelProperty(value = "部门ID")
    private Long deptId;

    @ExcelProperty(value = "岗位ID")
    private Long postId;

    @ExcelProperty(value = "角色", converter = ExcelDictConvert.class)
    @ExcelDictFormat(dictType = "panjia_employee_role")
    private String role;

    @ExcelProperty(value = "兼职状态", converter = ExcelDictConvert.class)
    @ExcelDictFormat(dictType = "panjia_part_time_status")
    private String partTimeStatus;

    @ExcelProperty(value = "入职日期")
    private LocalDate hireDate;

    @ExcelProperty(value = "初始职级", converter = ExcelDictConvert.class)
    @ExcelDictFormat(dictType = "panjia_employee_level")
    private String levelCode;

    @ExcelProperty(value = "缴纳社保")
    private String socialInsuranceEnabled;

    @ExcelProperty(value = "社保个人比例")
    private BigDecimal socialInsuranceRatio;

    @ExcelProperty(value = "公积金自缴")
    private BigDecimal housingFundAmount;

    @ExcelProperty(value = "商业保险")
    private String commercialInsurance;

    @ExcelProperty(value = "住宿舍")
    private String dormitoryEnabled;

    @ExcelProperty(value = "备注")
    private String remark;
}
