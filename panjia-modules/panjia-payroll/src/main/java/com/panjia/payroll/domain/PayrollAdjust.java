package com.panjia.payroll.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 调整/补发单 */
@Data
@TableName("pj_payroll_adjust")
public class PayrollAdjust implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long sourceBatchId;
    private String targetPeriod;
    private Long employeeId;
    private AdjustType adjustType;
    private BigDecimal amount;
    private Long contractId;
    private Long sourceFactId;
    private Long ruleSnapshotId;
    private Boolean badDebtFlag;
    private BigDecimal badDebtAmount;
    private String reason;
    private String status;
    private Long resultId;
    private Long operatorId;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
