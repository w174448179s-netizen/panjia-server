package com.panjia.importutil.export;

import com.panjia.importutil.template.model.ColumnDef;
import com.panjia.importutil.template.model.ImportTemplate;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFSheet;
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
 * 生成 Excel 内容：
 * <ul>
 *   <li>第 1 行：列名（含后缀 {@code *} 表示必填；必填列红字加粗）</li>
 *   <li>第 2 行起：用户填数据</li>
 *   <li>必填列：施加 Excel Data Validation（text length ≥ 1），客户端拦截非空</li>
 *   <li>表头行（第 1 行）冻结</li>
 * </ul>
 * <p>
 * V6.0.1 修订：删除原本第 2 行的「说明：列名后带 * 的为必填项，导入时不可为空。」
 * 提示文本。原因是说明文本会干扰数据读取：
 * <ul>
 *   <li>读取器按 {@code header_row} 配置定位表头行时，说明行会被误识别为数据行</li>
 *   <li>说明文本本身冗余——红色 {@code *} 已经足够视觉传达必填语义</li>
 * </ul>
 * 必填语义由「红字 + 后缀 {@code *}」单一视觉标识承担，不再叠加文字说明。
 * <p>
 * 模板层的"必填"现在有 3 道防线：
 * <ol>
 *   <li><b>视觉</b>：标红 + 后缀 {@code *} + 列宽自适应</li>
 *   <li><b>Excel 客户端</b>：Data Validation 拦截空值</li>
 *   <li><b>服务端校验</b>：{@code DefaultBasicValidator} 真正执行 {@code ColumnDef.isRequired} + {@code RuleDef("not_blank")}</li>
 * </ol>
 */
public final class TemplateExporter {

    /** 必填列后缀（视觉标识）；与 Excel 表头一起拼入单元格。 */
    private static final String REQUIRED_SUFFIX = " *";

    /** Excel Data Validation 应用的最大行号（= 模板预生成行数 − 1；1000 覆盖常见导入场景；溢出后 Excel 停止校验） */
    private static final int VALIDATION_MAX_ROW = 1000;

    private TemplateExporter() {
    }

    /**
     * 生成 Excel（.xlsx）模板字节。
     *
     * @param template 模板定义
     * @return XLSX 字节数组
     */
    public static byte[] toExcel(ImportTemplate template) {
        if (template == null || template.getColumns() == null || template.getColumns().isEmpty()) {
            throw new IllegalArgumentException("模板列定义为空，无法生成 Excel");
        }
        List<ColumnDef> columns = template.getColumns();
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            XSSFSheet sheet = wb.createSheet(
                (template.getSheetName() != null && !template.getSheetName().isBlank())
                    ? template.getSheetName() : "导入模板");

            // ===== 样式：必填列表头（红字加粗） + 普通表头（加粗） =====
            CellStyle requiredHeaderStyle = wb.createCellStyle();
            Font requiredFont = wb.createFont();
            requiredFont.setBold(true);
            requiredFont.setColor(IndexedColors.RED.getIndex());
            requiredHeaderStyle.setFont(requiredFont);

            CellStyle optionalHeaderStyle = wb.createCellStyle();
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            optionalHeaderStyle.setFont(boldFont);

            // ===== 第 0 行：表头 =====
            Row headerRow = sheet.createRow(0);
            DataValidationHelper dvh = sheet.getDataValidationHelper();
            for (int i = 0; i < columns.size(); i++) {
                ColumnDef col = columns.get(i);
                Cell cell = headerRow.createCell(i);
                String colName = col.getColName() != null ? col.getColName() : col.getField();
                if (col.isRequired()) {
                    // ★ 必填列：显示名 + " *" 后缀
                    cell.setCellValue(colName + REQUIRED_SUFFIX);
                    cell.setCellStyle(requiredHeaderStyle);
                } else {
                    cell.setCellValue(colName);
                    cell.setCellStyle(optionalHeaderStyle);
                }
                // 列宽：colName 字符数（按字节估算，中文=2 个 byte） + 4 padding
                int chars = colName == null ? 0 : colName.length();
                int width = Math.max(14, Math.min(36, chars + 4)) * 256;
                sheet.setColumnWidth(i, width);

                // ★ 必填列加 Excel Data Validation：文本长度 ≥ 1 = 非空
                // V6.0.1 起说明行删除，用户填数据从第 2 行（行号 1）开始，
                // Validation 范围从 (2,1000) 调整为 (1,1000)
                if (col.isRequired()) {
                    DataValidationConstraint constraint = dvh.createTextLengthConstraint(
                        DataValidationConstraint.OperatorType.BETWEEN, "1", "1048576");
                    CellRangeAddressList regions = new CellRangeAddressList(
                        1, VALIDATION_MAX_ROW, i, i);
                    DataValidation validation = dvh.createValidation(constraint, regions);
                    validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
                    String prompt = (colName == null ? "必填列" : colName) + " 不能为空";
                    validation.createErrorBox("必填项", prompt);
                    validation.setShowErrorBox(true);
                    validation.setSuppressDropDownArrow(true);
                    // 把单元格标 inputMessage（无错误时不显示错误，但留 input 给用户）
                    validation.createPromptBox("提示", "该列为必填项，不可为空");
                    validation.setShowPromptBox(true);
                    sheet.addValidationData(validation);
                }
            }

            // ===== 第 1 行：示例数据（用户填表参考，可整行删除） =====
            // V6.0.1 起保留示例行作为格式提示（按 type 给合理默认值：INT=1、DATE=2026-01-01 ...）。
            // 用户填数据从第 2 行（行号 1）起。
            Row sampleRow = sheet.createRow(1);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = sampleRow.createCell(i);
                cell.setCellValue(sampleValue(columns.get(i)));
            }

            // ★ 表头行冻结：第一行（用户滚动时始终可见）
            sheet.createFreezePane(0, 1);

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

            // 表头行：必填列加 " *" 后缀（与 Excel 一致）
            List<String> headers = new ArrayList<>();
            for (ColumnDef col : template.getColumns()) {
                String name = col.getColName() != null ? col.getColName() : col.getField();
                if (col.isRequired()) {
                    name = name + REQUIRED_SUFFIX;
                }
                headers.add(escape(name));
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
