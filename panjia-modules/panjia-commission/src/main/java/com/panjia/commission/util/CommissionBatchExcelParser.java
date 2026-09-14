package com.panjia.commission.util;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.dromara.common.core.exception.ServiceException;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 结佣批量 Excel 解析（§3.2 批量发起 / §3.3 批量审批）。
 * <p>
 * 真正的 .xlsx/.xls 二进制解析（Apache POI WorkbookFactory）。表头在前 3 行内自动探测：
 * 合同列名 {@code 合同号}（必需）；金额列名 {@code 实收金额}/{@code 当月实收业绩}/
 * {@code 实收业绩}/{@code 金额}（批量发起时可缺省）。
 */
public final class CommissionBatchExcelParser {

    private CommissionBatchExcelParser() {
    }

    private static final String[] AMOUNT_HEADERS = {"实收金额", "当月实收业绩", "实收业绩", "金额"};

    /**
     * 解析首个 sheet，返回 合同号 + 金额文本 行（金额列为 -1 时金额文本恒为空串）。
     */
    public static List<ContractAmountRow> parse(InputStream in) {
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new ServiceException("Excel 中没有可读取的 sheet");
            }

            int headerRowIdx = -1;
            int contractCol = -1;
            int amountCol = -1;
            int maxScan = Math.min(sheet.getFirstRowNum() + 3, sheet.getPhysicalNumberOfRows());
            for (int r = sheet.getFirstRowNum(); r <= maxScan; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                int cc = findColumn(row, "合同号", formatter);
                if (cc < 0) {
                    continue;
                }
                int ac = -1;
                for (String h : AMOUNT_HEADERS) {
                    ac = findColumn(row, h, formatter);
                    if (ac >= 0) {
                        break;
                    }
                }
                headerRowIdx = r;
                contractCol = cc;
                amountCol = ac;
                break;
            }
            if (headerRowIdx < 0) {
                throw new ServiceException("Excel 表头未找到「合同号」列");
            }

            List<ContractAmountRow> rows = new ArrayList<>();
            for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String contractNo = cellString(row.getCell(contractCol), formatter);
                String amountText = amountCol >= 0 ? cellString(row.getCell(amountCol), formatter) : "";
                if (contractNo.isBlank()) {
                    continue;
                }
                rows.add(new ContractAmountRow(contractNo.trim(), amountText == null ? "" : amountText.trim()));
            }
            return rows;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("Excel 解析失败（仅支持 .xlsx/.xls）：{}", e.getMessage());
        }
    }

    private static int findColumn(Row row, String header, DataFormatter formatter) {
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            String text = cellString(row.getCell(c), formatter);
            if (text != null && text.replace(" ", "").replace("　", "").contains(header)) {
                return c;
            }
        }
        return -1;
    }

    private static String cellString(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            // 合同号等长数字避免科学计数法：按 BigDecimal 原样输出
            double d = cell.getNumericCellValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                return new BigDecimal(cell.getNumericCellValue()).toBigInteger().toString();
            }
            return BigDecimal.valueOf(d).toPlainString();
        }
        return formatter.formatCellValue(cell);
    }

    /** 解析金额文本（去千分位/空白），非法返回 null。 */
    public static BigDecimal parseAmount(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.replace(",", "").replace("，", "").replaceAll("\\s", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 合同号 + 金额文本 行 */
    @Data
    @AllArgsConstructor
    public static class ContractAmountRow {
        private String contractNo;
        private String amountText;
    }
}
