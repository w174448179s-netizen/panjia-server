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
}
