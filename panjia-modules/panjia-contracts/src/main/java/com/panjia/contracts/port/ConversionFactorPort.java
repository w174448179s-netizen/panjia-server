package com.panjia.contracts.port;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Map;

/**
 * 折算因子查询端口 —— 折算比例取数的唯一公共出口。
 * <p>
 * 折算规则表 {@code pj_payroll_conversion_rule} 由薪酬域（panjia-payroll）持有；
 * 业绩域、结佣域均不得直连该表，一律经本端口读取（与 {@link ApprovalPort} /
 * {@link EmployeeMainDataQueryPort} 同为跨域只读门面）。
 * <p>
 * 取数口径：取 {@code effective_from <= 今天 <= effective_to}（effective_to 可空）
 * 且 version 最大的那条规则；查不到回退 bizType = {@code DEFAULT} 的规则；再无则返回 1。
 * <p>
 * 金额折算统一 {@link #convert(BigDecimal, BigDecimal)}：amount × factor，四舍五入保留 2 位。
 */
public interface ConversionFactorPort {

    /** 业务类型缺失时的兜底键（见 {@code pj_payroll_conversion_rule} 种子）。 */
    String DEFAULT_BIZ_TYPE = "DEFAULT";

    /**
     * 取单个业务类型的当前生效折算因子。
     *
     * @param bizType 业务类型（一手房/二手房/租赁/房产金融/家装荐客…）；为空时按 DEFAULT 处理
     * @return 折算因子，永不为 null（兜底 1）
     */
    BigDecimal factorOf(String bizType);

    /**
     * 批量取 bizType → factor（当前生效）。空集合安全。
     * <p>
     * 返回<b>可变</b> Map，且允许以 {@code null} 为键调用 {@code getOrDefault}，
     * 便于调用方直接对「可能为空的 bizType」取值而不必额外判空。
     *
     * @param bizTypes 业务类型集合（可含 null / 空白，将被忽略）
     * @return bizType → factor 映射
     */
    Map<String, BigDecimal> factorsOf(Collection<String> bizTypes);

    /**
     * 从 {@link #factorsOf(Collection)} 的批量结果中取某个键的因子（未命中回退 1）
     * —— 业务侧「取因子」的唯一公共方法，业务模块不得再自写 {@code getOrDefault(…, ONE)}。
     * <p>
     * 键类型泛型化，一份实现同时覆盖两种批量结果：
     * {@code bizType → factor}（K = String）与 {@code factId → factor}（K = Long），
     * 因此「按业务类型折算」与「按业绩事实折算」用的是同一个方法、同一套兜底规则。
     * <p>
     * 键为 {@code null} 时按 {@link #factorsOf(Collection)} 的约定取 DEFAULT 兜底值
     * （该结果集预置了 {@code null → DEFAULT}），与 {@link #factorOf(String)} 的空值口径一致；
     * 若批量结果里没有该键（例如调用方自建的 Map），回退 1。
     *
     * @param factorMap 批量结果，可为 null
     * @param key       业务类型 / 业绩事实 ID，可为 null
     * @return 折算因子，永不为 null（兜底 1）
     */
    default <K> BigDecimal factorOf(Map<K, BigDecimal> factorMap, K key) {
        if (factorMap == null) {
            return BigDecimal.ONE;
        }
        BigDecimal factor = factorMap.get(key);
        return factor != null ? factor : BigDecimal.ONE;
    }

    /**
     * 折算金额：amount × factor，四舍五入保留 2 位。
     *
     * @param amount 原始金额，为 null 时按 0.00 处理
     * @param factor 折算因子，为 null 时按 1 处理
     * @return 折算后金额（2 位小数），永不为 null
     */
    default BigDecimal convert(BigDecimal amount, BigDecimal factor) {
        if (amount == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal f = factor != null ? factor : BigDecimal.ONE;
        return amount.multiply(f).setScale(2, RoundingMode.HALF_UP);
    }
}
