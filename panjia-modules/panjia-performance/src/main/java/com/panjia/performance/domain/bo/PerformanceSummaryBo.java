package com.panjia.performance.domain.bo;

import lombok.Data;

/**
 * 业绩汇总报表查询条件。
 * <p>
 * 按新签业绩（PERF_EXPECT）统计，维度由 periodType 决定：
 * <ul>
 *   <li>MONTH - 按月汇总（返回该年每月一行）；</li>
 *   <li>QUARTER - 按季汇总（返回该年每季一行，quarter 可过滤单季）；</li>
 *   <li>YEAR - 按年汇总（返回该年合计一行）。</li>
 * </ul>
 */
@Data
public class PerformanceSummaryBo {

    /** 统计维度：MONTH-按月 / QUARTER-按季 / YEAR-按年（必填） */
    private String periodType;

    /** 年份 YYYY（必填） */
    private String year;

    /** 季度 1-4（periodType=QUARTER 时可选，为空查全年所有季度） */
    private Integer quarter;

    /** 部门 ID（可选，含子部门；经纪人强制本人，总监不限制，店长/财务限本部门子树） */
    private Long deptId;

    /** 员工 ID（可选：员工筛选） */
    private Long employeeId;

    /** 业务类型（可选，精确匹配） */
    private String bizType;
}
