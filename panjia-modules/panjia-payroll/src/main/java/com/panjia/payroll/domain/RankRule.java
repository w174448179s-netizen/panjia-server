package com.panjia.payroll.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 职级/提成规则 */
@Data
@TableName("pj_payroll_rank_rule")
public class RankRule implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String levelCode;
    private BigDecimal baseSalary;
    private BigDecimal baseRate;
    private BigDecimal minSalary;
    private BigDecimal teamRate;
    private BigDecimal personalRate;
    private String ruleContent;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
