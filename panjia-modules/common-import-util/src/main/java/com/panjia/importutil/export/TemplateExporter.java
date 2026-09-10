package com.panjia.importutil.export;

import com.panjia.importutil.template.model.ColumnDef;
import com.panjia.importutil.template.model.ImportTemplate;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 模板导出器：根据工具层 {@link ImportTemplate} 列定义生成 Excel/CSV 模板文件。
 * <p>
 * 生成内容：第 1 行表头（colName），第 2 行示例数据（按 type 给合理默认值）。
 * 业务域 Controller 调用后直接写入 HttpServletResponse 输出流。
 */
public final class TemplateExporter {

    private TemplateExporter() {
    }

    /**
     * 生成 Excel（.xlsx）模板字节（表头 + 示例行）。
     *
     * @param template 模板定义
     * @return XLSX 字节数组
     */
    public static byte[] toExcel(ImportTemplate template) {
        if (template == null || template.getColumns() == null || template.getColumns().isEmpty()) {
            throw new IllegalArgumentException("模板列定义为空，无法生成 Excel");
        }
        List<ColumnDef> columns = template.getColumns();
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            Sheet sheet = wb.createSheet("导入模板");

            // 表头样式：加粗
            CellStyle headerStyle = wb.createCellStyle();
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            headerStyle.setFont(boldFont);

            // 第 0 行：表头
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = headerRow.createCell(i);
                String colName = columns.get(i).getColName();
                cell.setCellValue(colName != null ? colName : columns.get(i).getField());
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 18 * 256);
            }

            // 第 1 行：示例数据
            Row sampleRow = sheet.createRow(1);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = sampleRow.createCell(i);
                cell.setCellValue(sampleValue(columns.get(i)));
            }

            wb.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Excel 模板导出失败", e);
        }
    }

    /**
     * 生成 CSV 模板字节（UTF-8 BOM + 表头 + 示例行）。
     *
     * @param template 模板定义
     * @return CSV 字节数组
     */
    public static byte[] toCsv(ImportTemplate template) {
        if (template == null || template.getColumns() == null || template.getColumns().isEmpty()) {
            return new byte[0];
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // UTF-8 BOM（让 Excel 正确识别编码）
        try {
            baos.write(new byte[]{(byte) 0xEF, (byte) 0xBF});
            Writer w = new OutputStreamWriter(baos, StandardCharsets.UTF_8);

            // 表头行
            List<String> headers = new ArrayList<>();
            for (ColumnDef col : template.getColumns()) {
                headers.add(escape(col.getColName()));
            }
            w.write(String.join(",", headers));
            w.write("\r\n");

            // 示例行
            List<String> samples = new ArrayList<>();
            for (ColumnDef col : template.getColumns()) {
                samples.add(escape(sampleValue(col)));
            }
            w.write(String.join(",", samples));
            w.write("\r\n");

            w.flush();
        } catch (Exception e) {
            throw new RuntimeException("模板导出失败", e);
        }
        return baos.toByteArray();
    }

    /**
     * 根据列类型生成合理示例值。
     */
    private static String sampleValue(ColumnDef col) {
        if (col.getEnumValues() != null && !col.getEnumValues().isEmpty()) {
            return col.getEnumValues().get(0);
        }
        String type = col.getType() == null ? "STRING" : col.getType().toUpperCase();
        return switch (type) {
            case "INT" -> "1";
            case "DECIMAL" -> "1000.00";
            case "DATE" -> "2026-01-01";
            case "BOOL" -> "是";
            default -> "";
        };
    }

    /**
     * CSV 转义：含逗号/引号/换行时用双引号包裹，内部引号翻倍。
     */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
