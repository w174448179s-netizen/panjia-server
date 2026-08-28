package com.panjia.license.util;

/**
 * 单调时钟容忍度常量。
 *
 * ⚠️ 铁律：5 分钟须定义为单一常量，全代码一处配置，禁止多处硬编码。
 * 所有涉及"5 分钟容忍"的逻辑必须引用此处的 MONOTONIC_TOLERANCE_MS，
 * 严禁在 MonotonicClock 或其他类中再写 300000 / 5 * 60 * 1000 等字面量。
 */
public final class MonotonicTolerance {

    /** 单调时钟回拨容忍度：5 分钟（毫秒） */
    public static final long MONOTONIC_TOLERANCE_MS = 5 * 60 * 1000L;

    private MonotonicTolerance() {
        // 工具类，禁止实例化
    }
}
