package com.panjia.importutil.convert;

import com.panjia.importutil.exception.ImportUtilException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * 基础类型转换器：STRING / INT / DECIMAL / DATE / BOOL。
 * <p>
 * 转换失败抛 {@link ImportUtilException}，由解析器捕获为 FieldError(TYPE_ERR)。
 */
public final class TypeConverter {

    private static final Set<String> TRUE_TOKENS = Set.of("是", "有", "true", "1", "√", "y", "yes");
    private static final Set<String> FALSE_TOKENS = Set.of("否", "无", "false", "0", "×", "x", "n", "no");

    private TypeConverter() {
    }

    /**
     * 按目标类型转换原始字符串。
     *
     * @param raw        原始字符串（已 trim，空白按 null 处理）
     * @param type       目标类型 STRING / INT / DECIMAL / DATE / BOOL
     * @param dateFormat 日期格式（type=DATE，可空默认 yyyy-MM-dd）
     * @return 转换后的值；raw 为空白返回 null
     */
    public static Object convert(String raw, String type, String dateFormat) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = type == null ? "STRING" : type.trim().toUpperCase();
        return switch (t) {
            case "STRING" -> raw;
            case "INT" -> toInt(raw);
            case "DECIMAL" -> toDecimal(raw);
            case "DATE" -> toDate(raw, dateFormat);
            case "BOOL" -> toBool(raw);
            default -> raw;
        };
    }

    private static Integer toInt(String raw) {
        try {
            // Excel 数值单元格可能给出 "12.0"
            return new BigDecimal(raw.trim()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new ImportUtilException("无法转换为整数: " + raw);
        }
    }

    private static BigDecimal toDecimal(String raw) {
        try {
            return parseDecimal(raw);
        } catch (NumberFormatException e) {
            throw new ImportUtilException("无法转换为数值: " + raw);
        }
    }

    /**
     * 解析数值输入，支持：
     * <ul>
     *   <li>千分位逗号：{@code 1,234.5}</li>
     *   <li>百分号后缀：{@code 5.00%} → 0.05（Excel 百分比格式单元格 /
     *       文本百分号统一按「% 表示除以 100」处理，下游字段如 shareRatio
     *       以小数量纲参与乘法运算）</li>
     * </ul>
     * 不带 % 的纯数字量纲不变（{@code 5.00} → 5.00）。
     *
     * @param raw 原始字符串（未 trim）
     * @return 数值
     * @throws NumberFormatException 非法数字
     */
    public static BigDecimal parseDecimal(String raw) {
        String v = raw.trim().replace(",", "");
        if (v.endsWith("%")) {
            return new BigDecimal(v.substring(0, v.length() - 1).trim())
                .divide(BigDecimal.valueOf(100));
        }
        return new BigDecimal(v);
    }

    private static final DateTimeFormatter[] FALLBACK_DATE_FORMATS = {
        DateTimeFormatter.ofPattern("yyyy-M-d"),
        DateTimeFormatter.ofPattern("yyyy/M/d"),
        DateTimeFormatter.ofPattern("yyyy年M月d日"),
        DateTimeFormatter.ofPattern("yyyy.MM.d"),
    };

    private static LocalDate toDate(String raw, String dateFormat) {
        String pattern = (dateFormat == null || dateFormat.isBlank()) ? "yyyy-MM-dd" : dateFormat.trim();
        String trimmed = raw.trim();
        try {
            return LocalDate.parse(trimmed, DateTimeFormatter.ofPattern(pattern));
        } catch (Exception e) {
            for (DateTimeFormatter fmt : FALLBACK_DATE_FORMATS) {
                try {
                    return LocalDate.parse(trimmed, fmt);
                } catch (Exception ignored) {
                }
            }
            throw new ImportUtilException("无法转换为日期(" + pattern + "): " + raw);
        }
    }

    private static Boolean toBool(String raw) {
        String v = raw.trim().toLowerCase();
        if (TRUE_TOKENS.contains(v)) {
            return Boolean.TRUE;
        }
        if (FALSE_TOKENS.contains(v)) {
            return Boolean.FALSE;
        }
        throw new ImportUtilException("无法转换为布尔值(是/否): " + raw);
    }
}
