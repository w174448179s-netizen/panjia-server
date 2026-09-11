package com.panjia.importutil.parser;

import com.panjia.importutil.config.ImportUtilProperties;
import com.panjia.importutil.convert.TypeConverter;
import com.panjia.importutil.dto.ParsedRow;
import com.panjia.importutil.dto.ParsedSheet;
import com.panjia.importutil.exception.ImportUtilException;
import com.panjia.importutil.template.model.ColumnDef;
import com.panjia.importutil.template.model.ImportTemplate;
import com.panjia.importutil.validate.FieldError;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV 解析器（UTF-8，逗号分隔，支持双引号包裹）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CsvFileParser implements FileParser {

    private final ImportUtilProperties properties;

    @Override
    public boolean supports(String originalFilename) {
        return originalFilename != null && originalFilename.toLowerCase().endsWith(".csv");
    }

    @Override
    public ParsedSheet parse(InputStream in, ImportTemplate template, String originalFilename) {
        Map<String, ColumnDef> headerIndex = new LinkedHashMap<>();
        for (ColumnDef col : template.getColumns()) {
            if (col.getColName() != null && !col.getColName().isBlank()) {
                headerIndex.put(col.getColName().trim(), col);
            }
        }

        ParsedSheet sheet = new ParsedSheet();
        sheet.setTemplateCode(template.getTemplateCode());
        sheet.setTemplateVersion(template.getTemplateVersion());

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<String> headers = null;
            String line;
            int dataSeq = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> cells = splitCsv(line);
                if (headers == null) {
                    headers = new ArrayList<>();
                    for (String c : cells) {
                        headers.add(c == null ? null : c.trim());
                    }
                    sheet.getHeaders().addAll(headers);
                    continue;
                }
                dataSeq++;
                if (dataSeq > properties.getMaxRows()) {
                    throw new ImportUtilException("单文件行数超过上限: " + properties.getMaxRows());
                }
                ParsedRow row = new ParsedRow();
                row.setRowNo(dataSeq);
                for (int i = 0; i < headers.size(); i++) {
                    String header = headers.get(i);
                    if (header == null || header.isBlank()) {
                        continue;
                    }
                    ColumnDef col = headerIndex.get(header);
                    if (col == null) {
                        continue;
                    }
                    String raw = i < cells.size() ? cells.get(i) : null;
                    String rawTrimmed = raw == null ? null : raw.trim();
                    try {
                        Object value = TypeConverter.convert(rawTrimmed, col.getType(), col.getDateFormat(), col.getTransform());
                        row.putValue(col.getField(), value, rawTrimmed);
                    } catch (ImportUtilException e) {
                        row.setValid(false);
                        row.putValue(col.getField(), null, rawTrimmed);
                        sheet.getErrors().add(new FieldError(dataSeq, col.getField(), rawTrimmed,
                            "TYPE_ERR", e.getMessage()));
                    }
                }
                sheet.getRows().add(row);
            }
        } catch (ImportUtilException e) {
            throw e;
        } catch (Exception e) {
            throw new ImportUtilException("CSV 解析失败", e);
        }
        log.debug("CSV 解析完成: {} 行, {} 个类型错误", sheet.getRows().size(), sheet.getErrors().size());
        return sheet;
    }

    /**
     * 极简 CSV 行切分（支持双引号包裹与转义 ""）。
     */
    private List<String> splitCsv(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                result.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        result.add(sb.toString().trim());
        return result;
    }
}
