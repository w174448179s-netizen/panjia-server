package com.panjia.payroll.adapter;

import com.panjia.contracts.port.ConversionFactorPort;
import com.panjia.payroll.domain.ConversionRule;
import com.panjia.payroll.mapper.ConversionRuleMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 折算因子端口实现（薪酬域）—— 折算规则表 {@code pj_payroll_conversion_rule} 的唯一读取者。
 * <p>
 * 业绩域 / 结佣域的「折算后金额」列全部经本实现取比例，业务侧只提供 bizType（或由自己域内的
 * 事实表反查出 bizType），不再各自写 SQL 连规则表 —— 改规则口径只动这一个类。
 */
@Component
@RequiredArgsConstructor
public class ConversionFactorAdapter implements ConversionFactorPort {

    private final ConversionRuleMapper conversionRuleMapper;

    @Override
    public BigDecimal factorOf(String bizType) {
        Map<String, BigDecimal> factors = currentFactors();
        BigDecimal factor = bizType == null ? null : factors.get(bizType);
        if (factor != null) {
            return factor;
        }
        BigDecimal fallback = factors.get(DEFAULT_BIZ_TYPE);
        return fallback != null ? fallback : BigDecimal.ONE;
    }

    @Override
    public Map<String, BigDecimal> factorsOf(Collection<String> bizTypes) {
        Map<String, BigDecimal> result = new HashMap<>();
        if (bizTypes == null || bizTypes.isEmpty()) {
            return result;
        }
        Map<String, BigDecimal> factors = currentFactors();
        BigDecimal fallback = factors.get(DEFAULT_BIZ_TYPE);
        if (fallback == null) {
            fallback = BigDecimal.ONE;
        }
        Set<String> distinct = new HashSet<>();
        for (String bizType : bizTypes) {
            if (StringUtils.isNotBlank(bizType)) {
                distinct.add(bizType);
            }
        }
        for (String bizType : distinct) {
            BigDecimal factor = factors.get(bizType);
            result.put(bizType, factor != null ? factor : fallback);
        }
        // 允许调用方以 null 为键取值（HashMap 支持），避免 Map.of() 在 getOrDefault(null) 时抛 NPE
        result.put(null, fallback);
        return result;
    }

    /**
     * 当前生效的 bizType → factor（同一 bizType 取 version 最大者）。
     * 规则表为小表（每条业务类型若干版本），直接全量取回内存过滤。
     */
    private Map<String, BigDecimal> currentFactors() {
        LocalDate today = LocalDate.now();
        Map<String, BigDecimal> effective = new HashMap<>();
        Map<String, Integer> versions = new HashMap<>();
        for (ConversionRule rule : conversionRuleMapper.selectList(null)) {
            if (rule.getBizType() == null || rule.getFactor() == null) {
                continue;
            }
            if (rule.getEffectiveFrom() != null && rule.getEffectiveFrom().isAfter(today)) {
                continue;
            }
            if (rule.getEffectiveTo() != null && rule.getEffectiveTo().isBefore(today)) {
                continue;
            }
            int version = rule.getVersion() != null ? rule.getVersion() : 0;
            Integer current = versions.get(rule.getBizType());
            if (current == null || version > current) {
                versions.put(rule.getBizType(), version);
                effective.put(rule.getBizType(), rule.getFactor());
            }
        }
        return effective;
    }
}
