package com.panjia.performance.domain.bo;

import lombok.Data;

/**
 * 业绩排行查询条件。
 * <p>
 * 按新签业绩（PERF_EXPECT）金额合计降序排行，分页返回。
 * 维度由 periodType 决定：MONTH/QUARTER/YEAR，统计该年份（+可选季度）内各员工新签业绩总额。
 */
@Data
public class PerformanceRankBo {

    /** 统计维度：MONTH-按月 / QUARTER-按季 / YEAR-按年（必填） */
    private String periodType;

    /** 年份 YYYY（必填） */
    private String year;

    /** 季度 1-4（periodType=QUARTER 时可选，为空查全年所有季度） */
    private Integer quarter;

    /** 部门 ID（可选，含子部门；经纪人强制本人，总监不限制，店长/财务限本部门子树） */
    private Long deptId;

    /** 业务类型（可选，精确匹配） */
    private String bizType;
}
