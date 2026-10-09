package com.panjia.people.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 积分每日明细 VO。
 */
@Data
public class ScoreDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long employeeId;
    private String employeeCode;
    private String employeeName;

    /** 填报日期 */
    private LocalDate pointDate;

    /** 填报时间 */
    private LocalDateTime submitTime;

    /** 当日积分 */
    private BigDecimal score;

    /** 是否计入总积分 */
    private Boolean isValid;

    /** 是否晚提交 */
    private Boolean isLateSubmit;
}
