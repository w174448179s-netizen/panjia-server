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
     * 按目标类型转换原始字符串（无 transform）。
     *
     * @see #convert(String, String, String, String)
     */
    public static Object convert(String raw, String type, String dateFormat) {
        return convert(raw, type, dateFormat, null);
    }

    /**
     * 按目标类型转换原始字符串。
     *
     * @param raw        原始字符串（已 trim，空白按 null 处理）
     * @param type       目标类型 STRING / INT / DECIMAL / DATE / BOOL
     * @param dateFormat 日期格式（type=DATE，可空默认 yyyy-MM-dd）
     * @param transform  模板转换规则（可空；逗号分隔，如 "percent" / "date_format:yyyy-MM-dd"）
     * @return 转换后的值；raw 为空白返回 null
     */
    public static Object convert(String raw, String type, String dateFormat, String transform) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = type == null ? "STRING" : type.trim().toUpperCase();
        return switch (t) {
            case "STRING" -> raw;
            case "INT" -> toInt(raw);
            case "DECIMAL" -> toDecimal(raw, transform);
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

    private static BigDecimal toDecimal(String raw, String transform) {
        try {
            // percent 规则：统一按百分数解析（"5.00%" 与 "5.00" 都落 0.05），只除一次 100
            if (hasPercentTransform(transform)) {
                return parsePercent(raw);
            }
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

    /**
     * 百分数解析（模板 transform=percent 用）：百分号可有可无，统一除以一次 100。
     * <ul>
     *   <li>{@code 5.00%} → 0.05</li>
     *   <li>{@code 5.00} → 0.05</li>
     * </ul>
     * 与 {@link #parseDecimal(String)} 的区别：后者只在值显式带 % 时才除 100；
     * percent 规则下列约定输入即百分比形式，裸数字也除。
     *
     * @param raw 原始字符串（未 trim）
     * @return 小数量纲数值
     * @throws NumberFormatException 非法数字
     */
    public static BigDecimal parsePercent(String raw) {
        String v = raw.trim().replace(",", "");
        if (v.endsWith("%")) {
            v = v.substring(0, v.length() - 1).trim();
        }
        return new BigDecimal(v).divide(BigDecimal.valueOf(100));
    }

    /** transform 串（逗号分隔多段）是否包含 percent 规则 */
    private static boolean hasPercentTransform(String transform) {
        return transform != null && transform.toLowerCase().contains("percent");
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
