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
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.event.AnalysisEventListener;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XLSX/XLS 解析器（fesod = EasyExcel/FastExcel fork，动态列不绑定实体）。
 * <p>
 * 表头按 {@link ColumnDef#getColName()} 匹配字段，值按 {@link ColumnDef#getType()} 转换；
 * 原始字符串保留在 rawValues，转换失败记 FieldError(TYPE_ERR)，不阻断解析。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XlsxFileParser implements FileParser {

    private final ImportUtilProperties properties;

    @Override
    public boolean supports(String originalFilename) {
        if (originalFilename == null) {
            return false;
        }
        String lower = originalFilename.toLowerCase();
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    @Override
    public ParsedSheet parse(InputStream in, ImportTemplate template, String originalFilename) {
        // 表头文本 → 列定义（trim 后匹配）
        Map<String, ColumnDef> headerIndex = new LinkedHashMap<>();
        for (ColumnDef col : template.getColumns()) {
            if (col.getColName() != null && !col.getColName().isBlank()) {
                headerIndex.put(col.getColName().trim(), col);
            }
        }

        ParsedSheet sheet = new ParsedSheet();
        sheet.setTemplateCode(template.getTemplateCode());
        sheet.setTemplateVersion(template.getTemplateVersion());

        int headerRow = template.getHeaderRow();

        AnalysisEventListener<Map<Integer, String>> listener = new AnalysisEventListener<>() {
            private final List<String> headers = new ArrayList<>();
            private int dataSeq = 0;

            @Override
            public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
                if (context.readRowHolder().getRowIndex() == headerRow) {
                    headers.clear();
                    headMap.values().forEach(h -> headers.add(h == null ? null : h.trim()));
                    sheet.getHeaders().addAll(headers);
                }
            }

            @Override
            public void invoke(Map<Integer, String> data, AnalysisContext context) {
                if (headers.isEmpty()) {
                    return;
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
                    String raw = data.get(i);
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

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
                log.debug("Excel 解析完成: {} 行, {} 个类型错误",
                    sheet.getRows().size(), sheet.getErrors().size());
            }
        };

        // 指定 sheet 名称时读取对应 sheet，否则读取第一个 sheet。
        // headRowNumber 必须覆盖 0..headerRow，否则 headerRow>0 的行会被当作数据行跳过
        // （fesod 默认 headRowNumber=1，仅第 0 行进 invokeHead）。
        if (template.getSheetName() != null && !template.getSheetName().isBlank()) {
            FesodSheet.read(in, listener).sheet(template.getSheetName().trim())
                .headRowNumber(headerRow + 1).doRead();
        } else {
            FesodSheet.read(in, listener).sheet()
                .headRowNumber(headerRow + 1).doRead();
        }

        return sheet;
    }
}
