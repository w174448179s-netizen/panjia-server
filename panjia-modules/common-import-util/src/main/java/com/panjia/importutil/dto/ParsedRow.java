package com.panjia.importutil.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析后的单行数据（纯内存，无业务语义）。
 */
@Data
public class ParsedRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 原始行号（1-based，数据行序号） */
    private int rowNo;

    /** field → 类型转换后的值（INT→Integer / DECIMAL→BigDecimal / DATE→LocalDate / BOOL→Boolean） */
    private Map<String, Object> values = new LinkedHashMap<>();

    /** field → 原始字符串（审计/回溯锚点，业务域落 raw_json 用） */
    private Map<String, String> rawValues = new LinkedHashMap<>();

    /** 基础格式校验是否通过（类型转换 + 必填） */
    private boolean valid = true;

    public void putValue(String field, Object value, String raw) {
        this.values.put(field, value);
        if (raw != null) {
            this.rawValues.put(field, raw);
        }
    }
}
