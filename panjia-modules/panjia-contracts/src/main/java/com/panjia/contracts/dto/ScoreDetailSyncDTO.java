package com.panjia.contracts.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 积分每日明细同步单条（导入域 → 员工域）。
 * <p>
 * 来源：《二手积分日报5.0版》导入批次的原始积分行（一人一天一行），
 * 由导入域 ScoreSummaryAggregator 聚合时附带每日明细，
 * 员工域 ScoreArchiveHandler 消费后写入 pj_people_score_detail。
 */
@Data
public class ScoreDetailSyncDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 工号（pj_people_employee.employee_code） */
    private String employeeCode;

    /** 填报日期 */
    private LocalDate pointDate;

    /** 填报时间（含时分秒，用于判定提交窗口） */
    private LocalDateTime submitTime;

    /** 当日积分 */
    private BigDecimal score;

    /** 是否晚提交（填报时间晚于 23:00） */
    private boolean lateSubmit;

    /** 是否计入总积分（早于 19:30 提交为 false） */
    private boolean valid;
}
