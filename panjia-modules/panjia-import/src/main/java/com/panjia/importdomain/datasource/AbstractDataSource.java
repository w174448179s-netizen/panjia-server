package com.panjia.importdomain.datasource;

import tools.jackson.databind.json.JsonMapper;
import com.panjia.importdomain.domain.ImportIssue;
import com.panjia.importdomain.domain.ImportIssueStatus;
import com.panjia.importdomain.domain.ImportIssueType;
import com.panjia.importutil.dto.ParsedRow;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据源抽象基类：封装问题构造、rawJson 序列化与类型读取。
 * <p>
 * V2.0 起类型转换由 common-import-util 完成（ParsedRow.values 为转换后值，
 * rawValues 为原始字符串），本基类只做读取，不再自行解析类型。
 */
@Slf4j
public abstract class AbstractDataSource implements DataSource {

    private static final JsonMapper OBJECT_MAPPER = new JsonMapper();

    protected ImportIssue issue(Integer rowNo, ImportIssueType type, String field, String rawValue, String msg) {
        ImportIssue issue = new ImportIssue();
        issue.setRowNo(rowNo);
        issue.setIssueType(type);
        issue.setFieldName(field);
        issue.setRawValue(rawValue);
        issue.setMessage(msg);
        issue.setStatus(ImportIssueStatus.OPEN);
        return issue;
    }

    /** 以原始字符串行（field→raw）序列化 raw_json */
    protected String toRawJson(ParsedRow row) {
        return writeJson(toRawJsonMap(row));
    }

    /**
     * 构造 raw_json 的有序 Map（field→原始字符串，空值不写入）。
     * 子类可在序列化前补充行内不存在、但由导入上下文推导的字段（如月度汇总按归属月补考勤日期）。
     */
    protected Map<String, Object> toRawJsonMap(ParsedRow row) {
        // rawValues 是 field→String，按 audit 锚点落库；空值不写入
        Map<String, Object> json = new LinkedHashMap<>();
        row.getRawValues().forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                json.put(k, v);
            }
        });
        return json;
    }

    /** 序列化 raw_json Map（失败兜底返回 {}） */
    protected String writeJson(Map<String, Object> json) {
        try {
            return OBJECT_MAPPER.writeValueAsString(json);
        } catch (Exception e) {
            log.warn("rawJson 序列化失败", e);
            return "{}";
        }
    }

    /** 取原始字符串（trim 后） */
    protected String str(ParsedRow row, String field) {
        String v = row.getRawValues().get(field);
        return v == null ? null : v.trim();
    }

    /** 取转换后的整数值（data_type=INT） */
    protected Integer integer(ParsedRow row, String field) {
        Object v = row.getValues().get(field);
        return v instanceof Integer i ? i : null;
    }

    /** 取转换后的数值（data_type=DECIMAL） */
    protected BigDecimal decimal(ParsedRow row, String field) {
        Object v = row.getValues().get(field);
        return v instanceof BigDecimal d ? d : null;
    }

    /** 取转换后的日期（data_type=DATE） */
    protected LocalDate date(ParsedRow row, String field) {
        Object v = row.getValues().get(field);
        return v instanceof LocalDate d ? d : null;
    }
}
