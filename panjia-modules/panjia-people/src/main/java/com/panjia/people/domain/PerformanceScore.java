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
 * upsert 写入），无人工登记入口。平均积分 = 总积分 / 出勤天数，
 * 绩效等级按平均分判定（A≥8 / B 6~8 / C<6），服务薪酬绩效扣点。
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

    /** 平均积分 = 总积分 / 出勤天数（出勤 0 天为 null） */
    private BigDecimal avgPoints;

    /** 绩效等级：A(≥8) B(6~8) C(<6)（出勤 0 天为 null） */
    private String grade;

    /** 提成扣点小数：A=0 B=-0.02 C=-0.04（快照展示；算薪扣点走规则快照 policy.points） */
    private BigDecimal deductRate;

    /** 数据来源：IMPORT=积分日报导入同步 */
    private String dataSource;

    @Version
    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
