package com.panjia.payroll.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 负工资结转余额 */
@Data
@TableName("pj_payroll_negative_balance")
public class NegativeBalance implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long employeeId;
    private String period;
    private BigDecimal amount;
    private String status;
    private String usedPeriod;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
