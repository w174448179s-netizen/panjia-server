package com.panjia.performance.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额工具类。
 * <p>
 * 提供业绩域金额计算的统一入口，确保精度一致。
 * 所有方法均为 null 安全，null 按 {@link BigDecimal#ZERO} 处理。
 * <p>
 * 全局取整规则：
 * <ul>
 *   <li>金额类结果统一保留 2 位小数（{@link #round2(BigDecimal)}）</li>
 *   <li>比例/系数类结果统一保留 6 位小数（{@link #round6(BigDecimal)}）</li>
 * </ul>
 */
public final class MoneyUtil {

    /** 零值常量 */
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /** 金额小数位数（2位） */
    private static final int SCALE_AMOUNT = 2;

    /** 比例小数位数（6位） */
    private static final int SCALE_RATIO = 6;

    private MoneyUtil() {
        // 工具类禁止实例化
    }

    /**
     * 四舍五入保留 2 位小数（全局唯一金额取整点）。
     *
     * @param value 原始值
     * @return 保留 2 位小数的结果；value 为 null 返回 0.00
     */
    public static BigDecimal round2(BigDecimal value) {
        if (value == null) {
            return ZERO.setScale(SCALE_AMOUNT, RoundingMode.HALF_UP);
        }
        return value.setScale(SCALE_AMOUNT, RoundingMode.HALF_UP);
    }

    /**
     * 四舍五入保留 6 位小数（比例/系数用）。
     *
     * @param value 原始值
     * @return 保留 6 位小数的结果；value 为 null 返回 0.000000
     */
    public static BigDecimal round6(BigDecimal value) {
        if (value == null) {
            return ZERO.setScale(SCALE_RATIO, RoundingMode.HALF_UP);
        }
        return value.setScale(SCALE_RATIO, RoundingMode.HALF_UP);
    }

    /**
     * 判断是否为零。
     *
     * @param value 待判断值
     * @return true 表示为零；value 为 null 视为零
     */
    public static boolean isZero(BigDecimal value) {
        if (value == null) {
            return true;
        }
        return value.compareTo(ZERO) == 0;
    }

    /**
     * 判断是否为正数。
     *
     * @param value 待判断值
     * @return true 表示大于零；value 为 null 返回 false
     */
    public static boolean isPositive(BigDecimal value) {
        if (value == null) {
            return false;
        }
        return value.compareTo(ZERO) > 0;
    }

    /**
     * 判断是否为负数。
     *
     * @param value 待判断值
     * @return true 表示小于零；value 为 null 返回 false
     */
    public static boolean isNegative(BigDecimal value) {
        if (value == null) {
            return false;
        }
        return value.compareTo(ZERO) < 0;
    }

    /**
     * 加法（null 安全，null 当 0）。
     *
     * @param a 被加数
     * @param b 加数
     * @return a + b 的结果（未取整，由调用方决定精度）
     */
    public static BigDecimal add(BigDecimal a, BigDecimal b) {
        BigDecimal safeA = (a == null) ? ZERO : a;
        BigDecimal safeB = (b == null) ? ZERO : b;
        return safeA.add(safeB);
    }

    /**
     * 减法（null 安全，null 当 0）。
     *
     * @param a 被减数
     * @param b 减数
     * @return a - b 的结果（未取整，由调用方决定精度）
     */
    public static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        BigDecimal safeA = (a == null) ? ZERO : a;
        BigDecimal safeB = (b == null) ? ZERO : b;
        return safeA.subtract(safeB);
    }

    /**
     * 乘法（结果 round2，用于金额计算）。
     *
     * @param a 被乘数
     * @param b 乘数
     * @return a × b 的结果，保留 2 位小数
     */
    public static BigDecimal multiply(BigDecimal a, BigDecimal b) {
        BigDecimal safeA = (a == null) ? ZERO : a;
        BigDecimal safeB = (b == null) ? ZERO : b;
        return round2(safeA.multiply(safeB));
    }

    /**
     * 乘法（结果 round6，用于比例/系数计算）。
     *
     * @param a 被乘数
     * @param b 乘数
     * @return a × b 的结果，保留 6 位小数
     */
    public static BigDecimal multiplyScale6(BigDecimal a, BigDecimal b) {
        BigDecimal safeA = (a == null) ? ZERO : a;
        BigDecimal safeB = (b == null) ? ZERO : b;
        return round6(safeA.multiply(safeB));
    }

    /**
     * 除法（结果 round2，用于金额计算）。
     * <p>
     * 除数为零或 null 时返回 0.00。
     *
     * @param a 被除数
     * @param b 除数
     * @return a ÷ b 的结果，保留 2 位小数
     */
    public static BigDecimal divide(BigDecimal a, BigDecimal b) {
        BigDecimal safeA = (a == null) ? ZERO : a;
        if (b == null || isZero(b)) {
            return ZERO.setScale(SCALE_AMOUNT, RoundingMode.HALF_UP);
        }
        return safeA.divide(b, SCALE_AMOUNT, RoundingMode.HALF_UP);
    }
}
