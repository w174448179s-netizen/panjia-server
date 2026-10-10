package com.panjia.performance.domain.bo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 合同明细懒加载查询条件（展开合同时按合同号查）。
 */
@Data
@NoArgsConstructor
public class PerformanceManageContractDetailBo {

    /** 归属期间 YYYY-MM */
    private String period;

    /** 事实口径：PERF_REAL / PERF_EXPECT */
    private String factType;

    /** 合同号集合 */
    private List<String> contractNos;

    /**
     * 订单号集合（可空）。同合同号挂多个订单号时传入以精确限定单订单明细，
     * 与调整链路「订单号优先匹配」口径一致；为空时保持业务键（订单号/合同号）双列匹配。
     */
    private List<String> orderNos;
}
