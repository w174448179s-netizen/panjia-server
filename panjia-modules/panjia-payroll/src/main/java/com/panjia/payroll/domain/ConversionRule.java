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

/** 业绩折算规则 */
@Data
@TableName("pj_payroll_conversion_rule")
public class ConversionRule implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String bizType;
    private BigDecimal factor;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Integer version;
    private LocalDateTime createTime;
}
