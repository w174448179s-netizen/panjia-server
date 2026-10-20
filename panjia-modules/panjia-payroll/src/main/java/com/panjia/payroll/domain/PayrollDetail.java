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
    /** 个税来源：ENGINE=内部引擎试算，EXTERNAL=外部个税系统（扣缴端）导入回补 */
    private String taxSource;
    /**
     * 结佣提成计算过程 JSON（数组）：[{month:"新签导入月",perf:折算业绩,rate:提点,income:提成金额},...]。
     * 仅当存在跨月回溯（新签导入月 ≠ 算薪月）时落地；全部当月结佣则为 null（展示保持原样）。
     */
    private String commissionCalcProcess;
    /**
     * 保底补足计算过程 JSON：{minSalary:保底线, compareLabel:比较基准名, compareValue:基准值,
     * steps?:[{label,value}] 基准构成, newsignSteps?:[{month,perf,rate,income}] 经纪人个人新签分笔回溯, fill:补足结果}。
     * 仅保底职级（minSalary &gt; 0）落地；未触发（fill=0）也落地，供前端展示"有没有触发、怎么算的"。
     */
    private String guaranteeCalcProcess;
    /**
     * 绩效方案计算过程 JSON：{schemeCode,schemeName,schemeType,grade?,tierMin?,tierMax?,perf?,deduct?,fee?}。
     * 按员工指派方案落库（未指派=默认方案）；未触发扣减也落地，供前端展示口径与推导链。
     */
    private String perfCalcProcess;

    /** 员工核对时间（纯留痕标记，员工在工资查询页点"核对"写入；批次锁定后不可核对，null=未核对） */
    private LocalDateTime ackTime;

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
    /** 门店社保业绩扣款（门店社保扣减标准 × 计缴参保人数，店长/总监「社保业绩扣款」列） */
    private BigDecimal deptEmployerSocialTotal;
    /** 门店社保扣减标准（每人每月固定额，DEPT 政策 socialStandard） */
    private BigDecimal deptSocialStandard;
    /** 门店计缴参保人数（非兼职 + 参保 + 个人社保比例>30%，按门店及下属组别人数合计） */
    private Integer deptInsuredCount;
    /** 新签与结佣差额（门店当月配置值，团队计薪业绩直接扣减） */
    private BigDecimal deptDiffAmount;
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
    /** 绩效方案金额扣款（业绩阶梯B版/店长积分绩效：未达标扣固定金额，正数=扣款，进应发扣减） */
    private BigDecimal perfDeductFee;
    /** 绩效方案编码（该批次冻结的方案，空=默认积分绩效） */
    private String perfSchemeCode;
    /** 提成点调整合计（未参保自动扣点+审批通过人工项，负=扣点） */
    private BigDecimal manualAdjust;
    /** 所有扣点合计（等级扣点 + 未参保自动扣点 + 人工调整，负=扣点，前端"绩效提成扣点"列统一取此值） */
    private BigDecimal totalDeduct;
    /** 伯乐加点（RATE_ADD模式：加点费率×达标千里马数封顶，已计入finalRate；AMOUNT_RATIO模式恒0） */
    private BigDecimal mentorAdd;
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
