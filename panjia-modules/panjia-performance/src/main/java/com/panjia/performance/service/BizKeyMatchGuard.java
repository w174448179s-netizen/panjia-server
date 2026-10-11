package com.panjia.performance.service;

import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.mapper.PerformanceFactMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 合同号/订单号匹配退化闸门（2026-10-11 定稿）。
 * <p>
 * 系统须同时兼容两种数据形态：
 * <ul>
 *   <li><b>同合同多订单</b>：合同号相同、订单号不同——内部业务判断必须合同号+订单号双键精确匹配，
 *       禁止退化按合同号，否则跨订单串单；</li>
 *   <li><b>贝壳新签源数据订单号误填成合同号</b>：新签事实 order_no 列存的是合同号，
 *       与实收订单号对不上——双键查不到时须能退化按合同号匹配，否则历史脏数据无法处理。</li>
 * </ul>
 * 统一规则：<b>双键未命中时，仅当退化查询的目标事实行在订单维度基数恰好为 1，才放行退化</b>。
 * 基数=1 表示该合同在对应口径下只有一单生意（脏数据/正常单订单皆然），按合同号关联不串；
 * 基数≥2（多订单）或 0（无事实）一律不退化，由调用方走空结果/人工兜底。
 * <p>
 * 注意 period/status 口径必须与调用方的退化查询完全一致（带期间的退化传期间，跨期退化传 null）。
 */
@Component
@RequiredArgsConstructor
public class BizKeyMatchGuard {

    private final PerformanceFactMapper factMapper;

    /**
     * 事实行操作单侧闸门：退化查询目标事实（指定期间/状态集合）去重订单号恰 1 个。
     *
     * @param period     退化查询的期间口径（可空=跨期）
     * @param factType   事实口径
     * @param statuses   退化查询的状态集合（可空=不限状态）
     * @param contractNo 合同号
     * @return true 表示可安全退化按合同号匹配
     */
    public boolean canDegradeToContract(String period, String factType,
                                        List<String> statuses, String contractNo) {
        if (StringUtils.isBlank(contractNo) || StringUtils.isBlank(factType)) {
            return false;
        }
        return factMapper.countDistinctOrderNos(period, factType, statuses, contractNo) == 1L;
    }

    /** ACTIVE PERF_EXPECT 口径快捷方法（新签调整/冲正/结佣装载常用）。 */
    public boolean canDegradeActiveExpect(String period, String contractNo) {
        return canDegradeToContract(period, FactType.PERF_EXPECT.getCode(),
            List.of(FactStatus.ACTIVE.name()), contractNo);
    }
}
