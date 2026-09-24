package com.panjia.importdomain.datasource;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 导入上下文：在解析/归一化全链路传递的不可变元数据。
 * <p>
 * templateCode 为本行数据命中的模板编码：历史工资多模板管线（HISTORY_PAYROLL，
 * 一模板一 sheet 循环解析）依赖它区分 sheet 来源（DataSource 据此分流 raw 表），
 * 单模板来源（KE_SIGNED/ATTENDANCE/…）该值与 sourceType 等价、不参与分流。
 */
@Data
@AllArgsConstructor
public class ImportContext {

    private final Long batchId;
    private final String batchNo;
    private final String period;
    private final String templateVersion;
    /** 命中的模板编码（pj_import_template.template_code） */
    private final String templateCode;
}
