package com.panjia.contracts.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 考勤月度指标（按员工聚合，供薪酬域考勤扣款计算）。
 * <p>
 * 来源：import 域 ATTENDANCE 归一化记录的 receivableAmount + extraJson
 * （lateCount/absentDays/leaveDays，钉钉月度汇总导入写入）。
 */
@Data
public class AttendanceMetricsDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 导入扣款金额（旧扁平模板「扣款金额」列；钉钉月度模板无此列，为 0） */
    private BigDecimal importedFee;

    /** 迟到次数（月合计） */
    private Integer lateCount;

    /** 旷工天数（月合计） */
    private BigDecimal absentDays;

    /** 请假天数（事假+病假合计，月合计） */
    private BigDecimal leaveDays;
}
