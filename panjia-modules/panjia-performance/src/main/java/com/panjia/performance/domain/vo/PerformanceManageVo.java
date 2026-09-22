package com.panjia.performance.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 业绩管理展示行（人 → 合同 → 明细 树表的明细层 DTO）。
 * <p>
 * 由业绩事实关联原始签约明细（订单号/合同号/房源地址/角色）与结佣单状态组装而成，
 * 供前端"业绩管理"页面按 员工 → 合同号 → 明细 三级展开展示。
 * <p>
 * 两个 Tab 共用同一 DTO：
 * <ul>
 *   <li>新签业绩 Tab：factType=PERF_EXPECT，amount=当月应收 receivable；</li>
 *   <li>结佣业绩 Tab：factType=PERF_REAL，amount=当月实收 received。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
public class PerformanceManageVo {

    /** 业绩事实 ID */
    private Long id;

    /** 事实状态（ACTIVE 有效 / VOIDED 已作废） */
    private String factStatus;

    /** 事实口径（PERF_REAL / PERF_EXPECT） */
    private String factType;

    /** 归属期间 YYYY-MM */
    private String period;

    // ==================== 合同维度 ====================

    /** 签约/认购日期时间（业绩业务发生日，含时分秒） */
    private LocalDateTime businessDate;

    /** 订单号（来自原始签约明细） */
    private String orderNo;

    /** 合同号（来自原始签约明细） */
    private String contractNo;

    /** 业务类型（新签/认购/...） */
    private String bizType;

    /** 房源地址（来自原始签约明细 raw_json.propertyAddress） */
    private String propertyAddress;

    /** 费用项（佣金/垫佣/服务费 等） */
    private String feeItem;

    // ==================== 人员维度 ====================

    /** 员工 ID */
    private Long employeeId;

    /** 员工姓名（签约人） */
    private String employeeName;

    /** 工号 */
    private String employeeCode;

    /** 门店/组别全路径（大区-门店-组；组级与门店同名或为空时只到大区-门店两级） */
    private String deptPath;

    /** 所属角色（角色类型编码，如 维护人/录入人/...） */
    private String roleType;

    /** 角色名（来自原始签约明细） */
    private String roleName;

    /** 角色占比（分账比例） */
    private BigDecimal shareRatio;

    // ==================== 金额 ====================

    /**
     * 业绩金额（调整后）：
     * <ul>
     *   <li>PERF_EXPECT → 当月应收（新签业绩）；</li>
     *   <li>PERF_REAL → 当月实收（结佣业绩）。</li>
     * </ul>
     */
    private BigDecimal amount;

    /**
     * 原始金额（调整前）。
     * 从未被调整过的事实，originalAmount = amount；
     * 被业绩调整覆盖的事实，originalAmount = 最早被冲销事实的金额（即导入原值）。
     */
    private BigDecimal originalAmount;

    // ==================== 结佣状态 ====================

    /** 是否已结算（存在已审批/已锁定的结佣明细引用该事实） */
    private Boolean settled;

    /** 结算日期（结佣单锁定时间，未结算为 null） */
    private LocalDateTime settleDate;

    /** 事实来源键（幂等标识，用于前端明细去重） */
    private String sourceKey;

    /** 折算后金额（amount × conversionFactor） */
    private BigDecimal convertedAmount;

    /** 折算后原始金额（originalAmount × conversionFactor） */
    private BigDecimal originalConvertedAmount;
}
