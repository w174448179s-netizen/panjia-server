package com.panjia.people.service;

import com.panjia.importutil.template.model.ColumnDef;
import com.panjia.people.dto.EmployeeVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 员工 Excel 导出器。
 * <p>
 * 列定义与导入模板（{@code pj_people_import_template.column_json}）保持一致，
 * 支持「导出 → 编辑 → 导入」的完整闭环：
 * <ul>
 *   <li>性别输出中文（男/女），导入侧 normalizeGender 反向兼容</li>
 *   <li>布尔列输出 是/否，导入 TypeConverter 支持</li>
 *   <li>部门全路径（大区-门店-组别）按 "-" 拆分为层级列</li>
 * </ul>
 */
@Slf4j
@Component
public class EmployeeExcelExporter {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String DEPT_SEP = "-";

    /**
     * 导出员工数据为 Excel 字节。
     *
     * @param columns   导入模板列定义（决定导出列与顺序）
     * @param employees 员工视图列表
     * @return .xlsx 字节数组
     */
    public byte[] export(List<ColumnDef> columns, List<EmployeeVO> employees) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("导出列定义为空");
        }
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet("员工名单");

            CellStyle headerStyle = buildHeaderStyle(wb);

            // 表头行（列名，与导入模板一致；不加 " *" 后缀，因为是数据文件不是模板）
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                ColumnDef col = columns.get(i);
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(col.getColName() != null ? col.getColName() : col.getField());
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, Math.max(14, Math.min(36,
                    (col.getColName() == null ? 0 : col.getColName().length()) + 4)) * 256);
            }
            sheet.createFreezePane(0, 1);

            // 数据行
            int rowIdx = 1;
            for (EmployeeVO vo : employees) {
                Row row = sheet.createRow(rowIdx++);
                String[] deptLevels = splitDept(vo.getDeptName());
                for (int i = 0; i < columns.size(); i++) {
                    String field = columns.get(i).getField();
                    String value = formatValue(field, vo, deptLevels);
                    if (value != null) {
                        row.createCell(i).setCellValue(value);
                    }
                }
            }

            wb.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("员工 Excel 导出失败", e);
            throw new RuntimeException("员工 Excel 导出失败: " + e.getMessage(), e);
        }
    }

    private CellStyle buildHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    /**
     * 按模板字段把员工视图转换为单元格字符串。
     */
    private String formatValue(String field, EmployeeVO vo, String[] deptLevels) {
        if (field == null) {
            return null;
        }
        return switch (field) {
            case "dept_level1" -> deptLevels.length > 0 ? deptLevels[0] : null;
            case "dept_level2" -> deptLevels.length > 1 ? deptLevels[1] : null;
            case "dept_level3" -> deptLevels.length > 2 ? deptLevels[2] : null;
            case "employee_code" -> vo.getEmployeeCode();
            case "employee_name" -> vo.getEmployeeName();
            case "level" -> vo.getLevelCode();
            case "post_names" -> vo.getPostNames() == null ? null : String.join("/", vo.getPostNames());
            case "phone" -> vo.getPhone();
            case "id_card" -> vo.getIdCard();
            case "residence" -> vo.getResidence();
            case "current_address" -> vo.getCurrentAddress();
            case "gender" -> genderLabel(vo.getGender());
            case "bank_name" -> vo.getBankName();
            case "bank_branch" -> vo.getBankBranch();
            case "bank_card_no" -> vo.getBankCardNo();
            case "report_date" -> dateStr(vo.getReportDate());
            case "hire_date" -> dateStr(vo.getHireDate());
            case "status" -> statusLabel(vo.getStatus());
            case "leave_date" -> dateStr(vo.getLeaveDate());
            case "social" -> yesNo(vo.getSocialInsured());
            case "housing" -> yesNo(vo.getHousingInsured());
            case "commercial" -> yesNo(vo.getCommercialInsured());
            case "dormitory" -> yesNo(vo.getDormitory());
            case "parttime" -> yesNo(vo.getIsPartTime());
            case "mentor_code" -> vo.getMentorCode();
            case "social_fee" -> decimalStr(vo.getSocialFee());
            case "commercial_fee" -> decimalStr(vo.getCommercialFee());
            case "housing_fund" -> decimalStr(vo.getHousingFund());
            case "dormitory_fee" -> decimalStr(vo.getDormitoryFee());
            default -> null;
        };
    }

    private String[] splitDept(String deptName) {
        if (deptName == null || deptName.isBlank()) {
            return new String[0];
        }
        return deptName.split(DEPT_SEP);
    }

    private String genderLabel(String gender) {
        if (gender == null) {
            return null;
        }
        return switch (gender.toUpperCase()) {
            case "MALE" -> "男";
            case "FEMALE" -> "女";
            default -> gender;
        };
    }

    private String statusLabel(String status) {
        if (status == null) {
            return null;
        }
        return switch (status.toUpperCase()) {
            case "ACTIVE" -> "在职";
            case "PARTTIME" -> "兼职";
            case "LEFT" -> "离职";
            case "PENDING" -> "待入职";
            case "VIRTUAL" -> "虚拟";
            default -> status;
        };
    }

    private String yesNo(Boolean b) {
        if (b == null) {
            return null;
        }
        return b ? "是" : "否";
    }

    private String dateStr(LocalDate d) {
        return d == null ? null : d.format(DATE_FMT);
    }

    private String decimalStr(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }
}
