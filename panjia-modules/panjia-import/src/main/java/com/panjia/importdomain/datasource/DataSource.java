package com.panjia.importdomain.datasource;

import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importutil.dto.ParsedSheet;

/**
 * 数据源 SPI（V2.0）。
 * <p>
 * 每类交易业务单据一个实现，负责把 common-import-util 解析出的 {@link ParsedSheet}
 * （内存行，已完成文件解析/类型转换/基础格式校验）转成对应 RawData 实体 + ImportIssue 列表。
 * <p>
 * 实现类只做结构转换与轻量业务校验，不做跨域查询；
 * 跨域校验（员工匹配）在归一化阶段由 ImportEngine 负责。
 */
public interface DataSource {

    /** 声明处理的 source_type */
    ImportSourceType sourceType();

    /**
     * 解析内存行为 RawData。
     *
     * @param sheet 工具层解析结果（含 ParsedRow 与基础错误）
     * @param ctx   导入上下文（批次号/归属月/模板版本）
     * @return 解析结果：RawData 列表 + 问题列表
     */
    ParseResult parse(ParsedSheet sheet, ImportContext ctx);
}
