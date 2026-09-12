package com.panjia.performance.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 业绩域配置。
 * <p>
 * 对应 application.yml：
 * <pre>
 * panjia:
 *   performance:
 *     conversion-enabled: true              # 是否启用业绩折算
 *     default-conversion-rate: 1.000000     # 默认折算系数
 *     default-share-ratio: 1.000000         # 默认分摊比例
 *     default-fact-type: PERF_REAL          # 默认事实口径
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "panjia.performance")
public class PerformanceProperties {

    /**
     * 是否启用业绩折算。
     * <p>
     * 启用后，业绩金额 = 原始金额 × 分摊比例 × 折算系数；
     * 关闭时，折算系数恒为 1，业绩金额 = 原始金额 × 分摊比例。
     * 默认启用。
     */
    private boolean conversionEnabled = true;

    /**
     * 默认折算系数。
     * <p>
     * 当业务类型未配置专属折算系数时使用此默认值，精度保留 6 位小数。
     */
    private BigDecimal defaultConversionRate = new BigDecimal("1.000000");

    /**
     * 默认分摊比例。
     * <p>
     * 当导入记录未指定分摊比例时使用此默认值（全额归属），精度保留 6 位小数。
     */
    private BigDecimal defaultShareRatio = new BigDecimal("1.000000");

    /**
     * 旧版默认事实口径（已不再消费）。
     * <p>
     * 历史上整批事实只按此配置生成单一口径；V4.2 双口径改造后，事实口径由归一化记录类型
     * 经 {@code PerformanceEngine.factTypesForRecord} 决定（SIGNED 双发 PERF_REAL+PERF_EXPECT，
     * NEW_SIGN 单发 PERF_EXPECT），配置项保留仅为向后兼容，修改它不会产生任何效果。
     */
    @Deprecated
    private String defaultFactType = "PERF_REAL";
}
