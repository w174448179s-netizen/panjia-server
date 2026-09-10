package com.panjia.importdomain.datasource;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 导入上下文：在解析/归一化全链路传递的不可变元数据。
 */
@Data
@AllArgsConstructor
public class ImportContext {

    private final Long batchId;
    private final String batchNo;
    private final String period;
    private final String templateVersion;
}
