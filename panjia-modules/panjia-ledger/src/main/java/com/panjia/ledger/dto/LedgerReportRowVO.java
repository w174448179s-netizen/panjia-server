package com.panjia.ledger.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 报表行。
 * <p>科目行按 pre-order 排列；spans[i] 表示第 i 级科目列的合并行数（0=被上级合并、不渲染）。
 * 合计行 totalRow=true，由前端跨 4 列展示 totalLabel。</p>
 */
@Data
public class LedgerReportRowVO {

    private Long subjectId;
    private String subjectName;
    private Integer level;
    private String direction;
    private String dataSource;

    /** true=合计营收/合计费用/营业利润等计算行 */
    private Boolean totalRow;
    private String totalLabel;

    /** 四级科目列合并信息，长度固定 4 */
    private int[] spans;

    /** 与 columns 等长；科目父级行为 null（不展示汇总值，仅叶子行展示） */
    private List<BigDecimal> values;

    private BigDecimal total;
}
