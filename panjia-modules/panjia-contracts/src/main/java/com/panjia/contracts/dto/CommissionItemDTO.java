package com.panjia.contracts.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 结佣明细 DTO（跨域契约，panjia-contracts 叶子模块）。
 * <p>
 * 结佣域经 {@code CommissionQueryPort} 对 payroll 域暴露的只读视图。
 * <p>
 * CI C15：DTO 必须含 {@code bizType}（业务类型），供 payroll 按业务类型应用折算规则；
 * 金额为<b>结佣业绩金额</b>（实收业绩原样透传），非佣金金额，本域不做任何折算。
 */
@Data
@NoArgsConstructor
public class CommissionItemDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 结佣明细 ID（来源为业绩事实透传时可为 null） */
    private Long itemId;

    /** 关联业绩事实 ID（DIFF 差额行为 null） */
    private Long performanceFactId;

    /** 业绩归属月（结算月 YYYY-MM） */
    private String period;

    /** 工资归属月（审批通过月，YYYY-MM） */
    private String approvedMonth;

    /** 员工 ID */
    private Long employeeId;

    /** 员工工号（业绩事实透传时携带） */
    private String employeeCode;

    /** 归属门店 ID */
    private Long deptId;

    /** 合同号（冻结快照 / 业绩事实透传） */
    private String contractNo;

    /** 订单号（业绩事实透传时携带，结佣明细无） */
    private String orderNo;

    /** 签约/认购日期（业绩事实透传） */
    private LocalDate businessDate;

    /** 签约/认购时间戳（raw_json.signDate 原始值，含时分秒） */
    private String signDate;

    /** 房源地址（业绩事实透传） */
    private String propertyAddress;

    /** 角色占比/分摊比例（业绩事实透传，仅展示） */
    private BigDecimal shareRatio;

    /** 业务类型（★ payroll 折算依据，CI C15） */
    private String bizType;

    /** 角色类型 */
    private String roleType;

    /** 费用项 */
    private String feeItem;

    /** 结佣业绩金额（原样透传，非佣金金额） */
    private BigDecimal amount;

    /** 结佣业绩折算后金额（amount × conversionFactor，展示用） */
    private BigDecimal convertedAmount;

    /** 明细状态（PENDING / APPROVED / REVERSED；业绩事实透传时为 null） */
    private String status;

    /** 数据来源：COMMISSION_ITEM-结佣明细 / PERFORMANCE_FACT-业绩事实只读透传 */
    private String source;
}
