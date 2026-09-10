package com.panjia.importdomain.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panjia.importdomain.domain.ImportIssue;
import com.panjia.importdomain.domain.ImportIssueStatus;
import com.panjia.importdomain.domain.ImportIssueType;
import com.panjia.importdomain.domain.raw.RawData;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 数据源抽象基类：封装行号、rawJson 序列化、问题构造与类型解析。
 */
@Slf4j
public abstract class AbstractDataSource implements DataSource {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter[] DATE_FORMATS = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("yyyyMMdd"),
    };

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

    protected String toRawJson(Map<String, Object> row) {
        try {
            return OBJECT_MAPPER.writeValueAsString(row);
        } catch (Exception e) {
            log.warn("rawJson 序列化失败", e);
            return "{}";
        }
    }

    protected String str(Map<String, Object> row, String field) {
        Object v = row.get(field);
        return v == null ? null : v.toString().trim();
    }

    protected Integer intVal(Map<String, Object> row, String field) {
        String s = str(row, field);
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected BigDecimal decimal(Map<String, Object> row, String field) {
        String s = str(row, field);
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected LocalDate date(Map<String, Object> row, String field) {
        String s = str(row, field);
        if (s == null || s.isEmpty()) {
            return null;
        }
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(s, fmt);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    /** 构造 RawData 公共字段（batchId/rowNo/rawJson/createTime） */
    protected void fillRawBase(RawData raw, ImportContext ctx, int rowNo, Map<String, Object> row) {
        // 公共字段由各实现通过 setter 设置；此方法作为约定占位
        // createTime 由 MyBatis-Plus 自动填充
    }
}
