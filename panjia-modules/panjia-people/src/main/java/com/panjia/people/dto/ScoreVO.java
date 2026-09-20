package com.panjia.people.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 绩效积分明细 VO（列表/详情）。
 */
@Data
public class ScoreVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long employeeId;

    /** 工号 */
    private String employeeCode;

    /** 姓名 */
    private String employeeName;

    private Long deptId;

    /** 部门全路径名 */
    private String deptName;

    /** 积分月份（当月 1 日） */
    private LocalDate scoreMonth;

    /** 当月总积分 */
    private BigDecimal totalPoints;

    /** 出勤天数（有日报的 DISTINCT 填报日期数） */
    private Integer attendDays;

    /** 平均积分 = 总积分 / 出勤天数 */
    private BigDecimal avgPoints;

    /** 绩效等级 A/B/C */
    private String grade;

    /** 提成扣点小数（0 / -0.02 / -0.04） */
    private BigDecimal deductRate;

    /** 当月晚提交次数（填报时间晚于 23:00 的天数） */
    private Integer lateSubmitCount;

    /** 积分扣款 = 晚提交次数 × 5 元/次 */
    private BigDecimal pointsFee;

    /** 行级锁定标记：该月审批 SUBMITTED/APPROVED 时为 true（前端隐藏提交入口） */
    private Boolean locked;
}
