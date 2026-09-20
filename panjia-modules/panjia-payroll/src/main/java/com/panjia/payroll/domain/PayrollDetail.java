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
    /** 积分扣款（积分日报晚提交处罚：晚提交次数 × 5 元/次） */
    private BigDecimal pointsFee;

    // 汇总
    private BigDecimal gross;
    private BigDecimal deduct;
    private BigDecimal tax;
    private BigDecimal net;
    private BigDecimal employerSocial;

    // 业绩溯源（导出展示用，落地避免前端反推误差）
    /** 当月新签业绩（折算后金额；店长=个人新签业绩，经纪人/总监无则 0） */
    private BigDecimal newSignPerformance;
    /** 当月结佣业绩（折算后金额，与提成同口径） */
    private BigDecimal commissionPerformance;
    /** 当月新签业绩提成比例（职级 personalRate，如 0.70） */
    private BigDecimal newSignRate;

    // 店长/总监 sheet 展示字段（V160012 落地，对齐天街工资表 2026.08 列结构）
    /** 门店当月新签计薪业绩合计（折算后，店长/总监展示用） */
    private BigDecimal deptNewSignTotal;
    /** 门店社保业绩扣款（门店全员公司承担社保合计，店长/总监「社保业绩扣款」列） */
    private BigDecimal deptEmployerSocialTotal;
    /** 店长团队提成比例（职级 teamRate，如 0.10） */
    private BigDecimal teamRate;
    /** 总监门店提成比例（跳点命中档 rate） */
    private BigDecimal storeRate;
    /** 店长保底工资（职级 minSalary，如 8000） */
    private BigDecimal minSalary;
    /** 总监全勤奖（policy.fullAttendance 默认 500） */
    private BigDecimal fullAttendance;
    /** 总监各门店提成明细 JSON（deptId/newSign/social/billable/rate/income，导出按门店分行对齐天街工资表） */
    private String directorStoreItems;

    // 溯源
    private BigDecimal finalRate;
    private String perfGrade;
    /** 绩效提成扣点（积分等级 A/B/C 对应扣点，A=0/B=-2%/C=-4%，负=扣点） */
    private BigDecimal perfDeduct;
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
