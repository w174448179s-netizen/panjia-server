package com.panjia.importdomain.template;

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
 * Excel 原始行读取器（动态列，不绑定实体）。
 * <p>
 * 读取结果为 List<Map<header, value>>，供 TemplateEngine 按 column_mapping 规整。
 */
@Slf4j
@Component
public class ExcelReader {

    /**
     * 读取 Excel 第一个 sheet，返回 header→value 的行列表。
     *
     * @param inputStream Excel 输入流
     * @param headerRow   表头行号（0-based）
     * @return 行列表
     */
    public List<Map<String, Object>> read(InputStream inputStream, int headerRow) {
        List<Map<String, Object>> rows = new ArrayList<>();
        FesodSheet.read(inputStream, new AnalysisEventListener<Map<Integer, String>>() {
            private List<String> headers = new ArrayList<>();

            @Override
            public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
                if (context.readRowHolder().getRowIndex() == headerRow) {
                    headers = new ArrayList<>(headMap.values());
                }
            }

            @Override
            public void invoke(Map<Integer, String> data, AnalysisContext context) {
                if (headers.isEmpty()) {
                    return;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    String header = headers.get(i);
                    if (header != null && !header.isBlank()) {
                        row.put(header.trim(), data.get(i));
                    }
                }
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
                log.debug("Excel 读取完成，共 {} 行", rows.size());
            }
        }).sheet().doRead();
        return rows;
    }
}
