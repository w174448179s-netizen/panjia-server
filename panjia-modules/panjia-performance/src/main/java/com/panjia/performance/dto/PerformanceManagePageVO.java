package com.panjia.performance.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 业绩管理人维度分页结果（懒加载树表）。
 * <p>
 * {@link #rows} 只包含当前页的「签约人」聚合行（每人一行：金额合计/合同数/明细数），
 * 人下的合同与明细不在本接口返回，由前端展开时调用 {@code /manage/details} 按员工懒加载；
 * {@link #total} 为符合条件的签约人数（非明细行数）；
 * {@link #summary} 为跨所有页的全局汇总，保证合计不随分页变化；
 * {@link #bizTypes} 为当前期间/口径下出现过的业务类型，供筛选下拉。
 */
@Data
@NoArgsConstructor
public class PerformanceManagePageVO {

    /** 签约人总数（分页 total） */
    private long total;

    /** 当前页签约人聚合行 */
    private List<PerformanceManageEmployeeVO> rows;

    /** 当前期间/口径下的业务类型集合（筛选下拉） */
    private List<String> bizTypes;

    /** 全局汇总（跨所有页） */
    private Summary summary;

    @Data
    @NoArgsConstructor
    public static class Summary {

        /** 签约人数（= total） */
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
