package com.panjia.contracts.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 积分月度汇总同步单条（导入域 → 员工域）。
 * <p>
 * 来源：《二手积分日报5.0版》导入批次归档后的原始积分行（一人一天一行），
 * 由员工域按工号匹配员工档案后 upsert 进 pj_people_performance_score。
 */
@Data
public class ScoreSummarySyncDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 工号（pj_people_employee.employee_code） */
    private String employeeCode;

    /** 积分月份（当月 1 日） */
    private LocalDate scoreMonth;

    /** 当月总积分（日报「今日总积分」合计） */
    private BigDecimal totalPoints;

    /** 当月出勤天数（有积分日报的 DISTINCT 填报日期数，平均积分分母） */
    private Integer attendDays;

    /** 当月晚提交次数（填报时间晚于 23:00 的天数，每天最多计 1 次；用于算薪积分扣款） */
    private Integer lateSubmitCount;
}
