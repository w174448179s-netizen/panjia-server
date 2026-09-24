package com.panjia.people.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

/**
 * 员工导入请求（multipart/form-data 绑定）。
 */
@Data
public class EmployeeImportQuery {

    /** 导入文件（XLSX/CSV） */
    private MultipartFile file;

    /**
     * 覆盖导入的生效时间（可选）：已有工号覆盖更新时，算薪数据列的切换基准
     * （旧事实结束日期、新事实生效日均置为该时间）；不传默认当前时间。
     * 新员工导入不受影响（算薪事实生效日=入职日）。
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate effectiveDate;
}
