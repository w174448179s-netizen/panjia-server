package com.panjia.performance.util;

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
 * 「合同号 + 金额」两列 Excel 解析工具（实收批量审批 / 结佣批量发起/审批共用）。
 * <p>
 * 真正的 .xlsx/.xls 二进制解析（Apache POI WorkbookFactory，两种格式均支持），
 * 不做 CSV 文本兜底。表头在前 3 行内自动探测：合同列名 {@code 合同号}，
 * 金额列名支持 {@code 实收金额}/{@code 实收业绩}/{@code 当月实收业绩}/{@code 金额}。
 */
public final class ExcelContractAmountParser {

    private ExcelContractAmountParser() {
    }

    private static final String[] AMOUNT_HEADERS = {"实收金额", "当月实收业绩", "实收业绩", "金额"};

    /**
     * 解析首个 sheet，返回 合同号 + 金额文本 行（跳过空行与重复合同号的后续行）。
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
                int ac = -1;
                for (String h : AMOUNT_HEADERS) {
                    ac = findColumn(row, h, formatter);
                    if (ac >= 0) {
                        break;
                    }
                }
                if (cc >= 0 && ac >= 0) {
                    headerRowIdx = r;
                    contractCol = cc;
                    amountCol = ac;
                    break;
                }
            }
            if (headerRowIdx < 0) {
                throw new ServiceException("Excel 表头未同时找到「合同号」列与金额列（实收金额/实收业绩/金额）");
            }

            List<ContractAmountRow> rows = new ArrayList<>();
            for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String contractNo = cellString(row.getCell(contractCol), formatter);
                String amountText = cellString(row.getCell(amountCol), formatter);
                if (contractNo.isBlank() && amountText.isBlank()) {
                    continue;
                }
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
