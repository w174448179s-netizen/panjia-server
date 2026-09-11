package com.panjia.performance.engine;

import com.panjia.performance.config.PerformanceProperties;
import com.panjia.performance.util.MoneyUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 业绩折算引擎。
 * <p>
 * 纯函数式无状态引擎，负责计算业绩金额。
 * 统一封装折算逻辑，确保全系统折算规则一致。
 * <p>
 * 计算公式：
 * <pre>
 * 业绩金额 = 原始金额 × 分摊比例 × 折算系数
 * </pre>
 * 折算关闭时，折算系数恒为 1，公式退化为：
 * <pre>
 * 业绩金额 = 原始金额 × 分摊比例
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class ConversionEngine {

    private final PerformanceProperties properties;

    /**
     * 计算业绩金额。
     * <p>
     * 公式：业绩金额 = originAmount × shareRatio × conversionRate
     * <p>
     * 参数 null 安全策略：
     * <ul>
     *   <li>originAmount 为 null → 返回 0</li>
     *   <li>shareRatio 为 null → 使用配置默认值</li>
     *   <li>conversionRate 为 null → 使用配置默认值；折算关闭时恒为 1</li>
     * </ul>
     * 结果通过 {@link MoneyUtil#round2(BigDecimal)} 取整（保留 2 位小数）。
     *
     * @param originAmount   原始金额
     * @param shareRatio     分摊比例
     * @param conversionRate 折算系数
     * @return 业绩金额（保留 2 位小数）
     */
    public BigDecimal calculate(BigDecimal originAmount, BigDecimal shareRatio, BigDecimal conversionRate) {
        // 原始金额为 null 直接返回 0
        if (originAmount == null) {
            return MoneyUtil.round2(BigDecimal.ZERO);
        }

        // 获取有效分摊比例（null 时用默认值）
        BigDecimal effectiveShareRatio = getEffectiveShareRatio(shareRatio);

        // 获取有效折算系数（null 时用默认值；折算关闭时恒为 1）
        BigDecimal effectiveConversionRate = getEffectiveConversionRate(conversionRate);

        // 业绩金额 = 原始金额 × 分摊比例 × 折算系数，结果 round2
        BigDecimal amount = originAmount.multiply(effectiveShareRatio).multiply(effectiveConversionRate);
        return MoneyUtil.round2(amount);
    }

    /**
     * 获取有效折算系数。
     * <p>
     * 优先级：显式传入值 > 配置默认值。
     * <p>
     * 折算关闭时（{@code !properties.isConversionEnabled()}），无论传入什么都返回 1。
     *
     * @param explicitRate 显式指定的折算系数（可为 null）
     * @return 有效的折算系数（保留 6 位小数）
     */
    public BigDecimal getEffectiveConversionRate(BigDecimal explicitRate) {
        // 折算关闭时，系数恒为 1
        if (!properties.isConversionEnabled()) {
            return MoneyUtil.round6(BigDecimal.ONE);
        }
        // 优先用显式值，其次用配置默认值
        if (explicitRate != null) {
            return MoneyUtil.round6(explicitRate);
        }
        return MoneyUtil.round6(properties.getDefaultConversionRate());
    }

    /**
     * 获取有效分摊比例。
     * <p>
     * 优先级：显式传入值 > 配置默认值。
     *
     * @param explicitRatio 显式指定的分摊比例（可为 null）
     * @return 有效的分摊比例（保留 6 位小数）
     */
    public BigDecimal getEffectiveShareRatio(BigDecimal explicitRatio) {
        if (explicitRatio != null) {
            return MoneyUtil.round6(explicitRatio);
        }
        return MoneyUtil.round6(properties.getDefaultShareRatio());
    }
}
