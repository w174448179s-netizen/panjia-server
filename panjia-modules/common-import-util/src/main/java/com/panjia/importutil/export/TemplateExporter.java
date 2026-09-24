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
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            writeSheet(wb, template, 0);
            wb.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Excel 模板导出失败", e);
        }
    }

    /**
     * 生成多 Sheet 工作簿模板字节（历史工资导入等多模板场景）。
     * <p>每个模板渲染一个 Sheet（表头 + 示例行 + 必填校验 + 表头冻结），
     * Sheet 名取 {@code sheetName} 并做去重；模板表头行号 &gt; 1 时在其前补空行
     * （保持与原始文件行号语义一致，如店长工资 R1 为标题行）。
     * 列名展示剥离 {@code @N} 出现序后缀（如「姓名@2」→「姓名」）。
     *
     * @param templates 模板列表（顺序即 Sheet 顺序）
     * @return XLSX 字节数组
     */
    public static byte[] toWorkbook(List<ImportTemplate> templates) {
        if (templates == null || templates.isEmpty()) {
            throw new IllegalArgumentException("模板列表为空，无法生成工作簿");
        }
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            java.util.Set<String> usedNames = new java.util.HashSet<>();
            int idx = 0;
            for (ImportTemplate template : templates) {
                if (template.getColumns() == null || template.getColumns().isEmpty()) {
                    continue;
                }
                String base = (template.getSheetName() != null && !template.getSheetName().isBlank())
                    ? template.getSheetName()
                    : (template.getTemplateCode() != null ? template.getTemplateCode() : "Sheet" + (idx + 1));
                // Excel sheet 名 31 字符上限 + 去重
                String name = base.length() > 31 ? base.substring(0, 31) : base;
                String unique = name;
                int seq = 2;
                while (!usedNames.add(unique)) {
                    String suffix = "-" + seq++;
                    unique = (name.length() + suffix.length() > 31
                        ? name.substring(0, 31 - suffix.length()) : name) + suffix;
                }
                writeSheet(wb, template, idx, unique);
                idx++;
            }
            wb.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Excel 模板工作簿导出失败", e);
        }
    }

    /**
     * 向工作簿写入一个模板 Sheet。
     *
     * @param wb        工作簿
     * @param template  模板定义
     * @param sheetIdx  Sheet 序号（单模板场景传 0）
     * @param sheetName 多模板场景的 Sheet 名（单模板传 null 用模板自带名）
     */
    private static void writeSheet(XSSFWorkbook wb, ImportTemplate template, int sheetIdx, String... sheetName) {
        List<ColumnDef> columns = template.getColumns();
        String name = (sheetName != null && sheetName.length > 0 && sheetName[0] != null)
            ? sheetName[0]
            : ((template.getSheetName() != null && !template.getSheetName().isBlank())
                ? template.getSheetName() : "导入模板");
        XSSFSheet sheet = wb.createSheet(name);

        // 表头行偏移：模板 header_row > 1 时在其前补空行（保持与原始文件行号语义一致）
        int offset = template.getHeaderRow() > 1 ? template.getHeaderRow() - 1 : 0;
        for (int r = 0; r < offset; r++) {
            sheet.createRow(r);
        }

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

            // ===== 表头行（header_row 偏移后）=====
            Row headerRow = sheet.createRow(offset);
            DataValidationHelper dvh = sheet.getDataValidationHelper();
            for (int i = 0; i < columns.size(); i++) {
                ColumnDef col = columns.get(i);
                Cell cell = headerRow.createCell(i);
                // 展示名剥离 @N 出现序后缀（「姓名@2」→「姓名」），普通列名无变化
                String colName = com.panjia.importutil.template.HeaderNames.baseName(
                    col.getColName() != null ? col.getColName() : col.getField());
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
                if (col.isRequired()) {
                    DataValidationConstraint constraint = dvh.createTextLengthConstraint(
                        DataValidationConstraint.OperatorType.BETWEEN, "1", "1048576");
                    CellRangeAddressList regions = new CellRangeAddressList(
                        offset + 1, VALIDATION_MAX_ROW, i, i);
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

            // ===== 示例数据行（表头下一行，用户填表参考，可整行删除） =====
            Row sampleRow = sheet.createRow(offset + 1);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = sampleRow.createCell(i);
                cell.setCellValue(sampleValue(columns.get(i)));
            }

            // ★ 表头行冻结：表头及其上方空行（用户滚动时始终可见）
            sheet.createFreezePane(0, offset + 1);
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
