package com.panjia.payroll.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额精度工具：全程 BigDecimal，落地一次取整到分。
 */
public final class MoneyUtil {

    public static final BigDecimal ZERO = BigDecimal.ZERO;
    public static final BigDecimal HUNDRED = new BigDecimal("100");

    private MoneyUtil() {
    }

    /** 四舍五入到分（2 位小数） */
    public static BigDecimal round2(BigDecimal v) {
        if (v == null) {
            return ZERO;
        }
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** 保留 6 位小数（中间值） */
    public static BigDecimal round6(BigDecimal v) {
        if (v == null) {
            return ZERO;
        }
        return v.setScale(6, RoundingMode.HALF_UP);
    }

    public static BigDecimal nvl(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    public static boolean isZero(BigDecimal v) {
        return v == null || v.compareTo(ZERO) == 0;
    }

    public static boolean isNegative(BigDecimal v) {
        return v != null && v.compareTo(ZERO) < 0;
    }

    public static boolean isPositive(BigDecimal v) {
        return v != null && v.compareTo(ZERO) > 0;
    }
}
