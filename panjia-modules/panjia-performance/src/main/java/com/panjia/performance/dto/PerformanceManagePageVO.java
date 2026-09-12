package com.panjia.performance.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 业绩管理分页结果（懒加载树表），泛型 T 为行维度聚合 VO。
 * <p>
 * 人维度：T={@link PerformanceManageEmployeeVO}，{@link #total} 为签约人数；
 * 合同维度：T={@link PerformanceManageContractVO}，{@link #total} 为合同数。
 * <p>
 * {@link #rows} 只包含当前页的聚合行，其下子级由懒加载接口按需查询；
 * {@link #summary} 为跨所有页的全局汇总，保证合计不随分页变化；
 * {@link #bizTypes} 为当前期间/口径下出现过的业务类型，供筛选下拉。
 */
@Data
@NoArgsConstructor
public class PerformanceManagePageVO<T> {

    /** 聚合行数（人维度=签约人数；合同维度=合同数） */
    private long total;

    /** 当前页聚合行 */
    private List<T> rows;

    /** 当前期间/口径下的业务类型集合（筛选下拉） */
    private List<String> bizTypes;

    /** 全局汇总（跨所有页） */
    private Summary summary;

    @Data
    @NoArgsConstructor
    public static class Summary {

        /** 签约人数（去重） */
        private long employeeCount;

        /** 合同数（按合同号去重） */
        private long contractCount;

        /** 明细条数 */
        private long detailCount;

        /** 未结算条数 */
        private long unsettledCount;

        /** 金额合计（PERF_EXPECT=应收 / PERF_REAL=实收） */
        private BigDecimal totalAmount;
    }
}
