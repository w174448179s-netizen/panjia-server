package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 绩效积分月度汇总（一人一月一行）。
 * <p>
 * 数据全部来自《二手积分日报5.0版》导入同步（ScoreService.syncScoreSummaries
 * upsert 写入），无人工登记入口。仅存原始事实（总积分/出勤天数/晚提交次数）；
 * 平均积分、绩效等级、提成扣点、积分扣款为派生字段，查询时按
 * {@link ScoreGradePolicy} 实时计算，不落库。
 */
@Data
@TableName("pj_people_performance_score")
public class PerformanceScore implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 员工 ID（pj_people_employee.employee_id） */
    private Long employeeId;

    /** 积分月份（当月 1 日） */
    private LocalDate scoreMonth;

    /** 当月总积分（日报「今日总积分」合计） */
    private BigDecimal totalPoints;

    /** 出勤天数（有日报的 DISTINCT 填报日期数） */
    private Integer attendDays;

    /** 当月晚提交次数（填报时间晚于 23:00 的天数，每天最多 1 次） */
    private Integer lateSubmitCount;

    /** 数据来源：IMPORT=积分日报导入同步 */
    private String dataSource;

    @Version
    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
