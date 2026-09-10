package com.panjia.importdomain.template;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 列映射条目（模板 column_mapping JSONB 数组元素）。
 * <p>
 * 字段对齐 ImportTemplateStaticValidationTest 校验规约：
 * source_column（A-Z 列号）、source_header（表头文本）、target_field（标准化字段名）、
 * transform（lookup:dict_type）、default_value（字符串或 null）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ColumnMapping implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Excel 列号（A-Z） */
    @JsonProperty("source_column")
    private String sourceColumn;

    /** Excel 表头文本（映射键） */
    @JsonProperty("source_header")
    private String sourceHeader;

    /** 标准化字段名（RawData 字段名） */
    @JsonProperty("target_field")
    private String targetField;

    /** 转换规则，如 lookup:panjia_biz_type */
    @JsonProperty("transform")
    private String transform;

    /** 默认值（字符串或 null） */
    @JsonProperty("default_value")
    private String defaultValue;

    /** 字段类型：string/int/decimal/date */
    @JsonProperty("data_type")
    private String type;

    /** 是否必填 */
    @JsonProperty("required")
    private Boolean required;
}
