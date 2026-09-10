package com.panjia.importdomain.datasource;

import com.panjia.importdomain.domain.ImportSourceType;

import java.util.Map;

/**
 * 业务唯一键生成 SPI（V1.4 §3.3 source_key）。
 * <p>
 * 按数据源类型生成业务唯一键，供下游去重与对账。
 */
public interface SourceKeyGenerator {

    ImportSourceType sourceType();

    /**
     * 从标准化原始行生成 source_key。
     *
     * @param row 标准化原始行
     * @return 业务唯一键；无法生成返回 null
     */
    String generate(Map<String, Object> row);
}
