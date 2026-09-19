package com.panjia.payroll.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.panjia.common.constant.PanjiaTransConstant;
import lombok.Data;
import org.dromara.common.translation.annotation.Translation;
import org.dromara.common.translation.constant.TransConstant;

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
    /** 提成点调整合计（未参保自动扣点+审批通过人工项，负=扣点） */
    private BigDecimal manualAdjust;
    /** 提成点调整命中项溯源 JSON（adjustId/type/rate/reason/source） */
    private String rateAdjustJson;
    private Long ruleSnapshotId;
    private Long empSnapshotId;

    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /**
     * 员工姓名（非入库字段；序列化时按 {@link #employeeId} 从员工档案表翻译）。
     * 业务角色无 system:user:query 权限，前端不查员工全量表，由后端统一翻译。
     */
    @TableField(exist = false)
    @Translation(type = PanjiaTransConstant.EMPLOYEE_ID_TO_NAME, mapper = "employeeId")
    private String employeeName;

    /** 工号（非入库字段；按 employeeId 翻译） */
    @TableField(exist = false)
    @Translation(type = PanjiaTransConstant.EMPLOYEE_ID_TO_CODE, mapper = "employeeId")
    private String employeeCode;

    /** 门店名称（非入库字段；按 deptId 从 sys_dept 翻译） */
    @TableField(exist = false)
    @Translation(type = TransConstant.DEPT_ID_TO_NAME, mapper = "deptId")
    private String deptName;
}
