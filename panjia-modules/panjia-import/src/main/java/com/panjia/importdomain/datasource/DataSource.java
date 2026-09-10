package com.panjia.importdomain.datasource;

import com.panjia.importdomain.domain.ImportSourceType;

import java.util.List;
import java.util.Map;

/**
 * 数据源 SPI（V1.4 §3.6）。
 * <p>
 * 每类导入源一个实现，负责把标准化原始行（按模板 column_mapping 规整后的 Map）
 * 转成对应 RawData 实体 + ImportIssue 列表。
 * <p>
 * 实现类只做结构转换与轻量校验（必填/类型/枚举），不做跨域查询；
 * 跨域校验（员工匹配/部门解析）在归一化阶段由 Normalizer 负责。
 */
public interface DataSource {

    /** 声明处理的 source_type */
    ImportSourceType sourceType();

    /**
     * 解析标准化原始行。
     *
     * @param rawRows 已按模板列映射规整的原始行（key=标准化字段名，value=原始值）
     * @param ctx     导入上下文（批次号/归属月/模板版本）
     * @return 解析结果：RawData 列表 + 问题列表
     */
    ParseResult parse(List<Map<String, Object>> rawRows, ImportContext ctx);
}
