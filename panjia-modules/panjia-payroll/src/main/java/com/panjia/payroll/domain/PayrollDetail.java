package com.panjia.payroll.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 工资明细（一人一条） */
@Data
@TableName("pj_payroll_detail")
public class PayrollDetail implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long batchId;
    private String period;
    private Long employeeId;
    private Long deptId;
    private String levelCode;
    private EmployeeRole employeeRole;
    private Boolean isPartTime;

    // 收入项
    private BigDecimal commissionIncome;
    private BigDecimal teamIncome;
    private BigDecimal personalNewsignIncome;
    private BigDecimal storeIncome;
    private BigDecimal baseSalary;
    private BigDecimal guaranteeFill;
    private BigDecimal mentorBonus;
    private BigDecimal bonus;
    private BigDecimal otherIncome;

    // 支出项（不含个税）
    private BigDecimal socialFee;
    private BigDecimal housingFund;
    private BigDecimal attendanceFee;
    private BigDecimal pointsFee;
    private BigDecimal commercialInsurance;
    private BigDecimal dormitoryFee;
    private BigDecimal negativeCarryover;
    private BigDecimal otherDeduct;

    // 汇总
    private BigDecimal gross;
    private BigDecimal deduct;
    private BigDecimal tax;
    private BigDecimal net;
    private BigDecimal employerSocial;

    // 溯源
    private BigDecimal finalRate;
    private String perfGrade;
    private Long ruleSnapshotId;
    private Long empSnapshotId;

    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
