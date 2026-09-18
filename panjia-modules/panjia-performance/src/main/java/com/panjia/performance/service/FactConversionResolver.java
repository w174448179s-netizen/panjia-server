package com.panjia.performance.service;

import com.panjia.contracts.port.ConversionFactorPort;
import com.panjia.performance.mapper.PerformanceFactMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业绩域「折算因子」解析器 —— 只负责把业绩域自有标识解析成 bizType 再取因子。
 * <p>
 * 与 {@link ConversionFactorPort} 的分工（一个能力只有一份实现，避免两处维护同一套取值逻辑）：
 * <ul>
 *   <li><b>因子怎么取、金额怎么乘</b>：{@link ConversionFactorPort}（公共方法；规则表
 *       {@code pj_payroll_conversion_rule} 的唯一读取者在薪酬域 {@code ConversionFactorAdapter}）；</li>
 *   <li><b>factId / 合同号怎么变成 bizType</b>：本类。这一步必须查业绩域自有的
 *       {@code pj_perf_fact}，契约层与薪酬域都看不到，只能落在本域。</li>
 * </ul>
 * 因此本类<b>不</b>提供 {@code factorOf(bizType)} / {@code convert(amount, factor)} 之类的转发方法：
 * 业务侧需要单值因子或金额乘算时直接注入 {@link ConversionFactorPort} 调用。
 * 转发壳没有任何抽象价值，还会造成「同名类 → Spring bean 名冲突」的隐患
 * （本模块曾有一个同名 {@code ConversionService} 与结佣域的同名类撞 bean 名，启动即
 * {@code ConflictingBeanDefinitionException}）。
 */
@Service
@RequiredArgsConstructor
public class FactConversionResolver {

    private final PerformanceFactMapper factMapper;
    private final ConversionFactorPort conversionFactorPort;

    /**
     * 按业绩事实 ID 批量解析折算因子（factId → factor）。
     * <p>
     * 先在业绩域内把 factId 批量解析成 bizType，再统一向规则处取比例；空集合安全。
     * 解析不出 bizType（字段为空 / 事实不存在）的键按 DEFAULT 兜底，与
     * {@link ConversionFactorPort#factorOf(String)} 的空值口径一致。
     *
     * @param factIds 业绩事实 ID 集合
     * @return factId → factor 映射；空集合返回空 Map
     */
    public Map<Long, BigDecimal> factorByFactIds(Collection<Long> factIds) {
        Map<Long, BigDecimal> result = new HashMap<>();
        if (factIds == null || factIds.isEmpty()) {
            return result;
        }
        List<Map<String, Object>> rows = factMapper.selectBizTypeByFactIds(new HashSet<>(factIds));
        Set<String> bizTypes = new HashSet<>();
        for (Map<String, Object> row : rows) {
            bizTypes.add(bizTypeOf(row));
        }
        Map<String, BigDecimal> factors = conversionFactorPort.factorsOf(bizTypes);
        for (Map<String, Object> row : rows) {
            Object id = row.get("factId");
            if (id == null) {
                continue;
            }
            result.put(((Number) id).longValue(), conversionFactorPort.factorOf(factors, bizTypeOf(row)));
        }
        return result;
    }

    /**
     * 按合同号 + 期间 + 事实口径解析折算因子（合同级调整无 factId 时使用）。
     *
     * @param period     期间
     * @param contractNo 合同号 / 订单号（业务键）
     * @param factType   事实口径
     * @return 折算因子；查不到返回 1
     */
    public BigDecimal factorByContract(String period, String contractNo, String factType) {
        if (StringUtils.isBlank(period) || StringUtils.isBlank(contractNo)) {
            return BigDecimal.ONE;
        }
        return conversionFactorPort.factorOf(
            factMapper.selectBizTypeByContract(period, contractNo, factType));
    }

    /** 读取 Mapper 返回行的 bizType 列（列别名 {@code bizType}）。 */
    private String bizTypeOf(Map<String, Object> row) {
        Object bizType = row.get("bizType");
        return bizType == null ? null : String.valueOf(bizType);
    }
}
