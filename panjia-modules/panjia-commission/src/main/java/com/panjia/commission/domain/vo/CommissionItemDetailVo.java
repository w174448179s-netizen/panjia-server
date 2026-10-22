package com.panjia.commission.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 结佣申请单详情·每人明细行（结佣明细页详情弹窗展示用）。
 * <p>
 * 列口径对齐「实收明细详情」页：门店/组别、工号、姓名、所属角色、角色占比、应收金额、结佣金额。
 * 应收金额按同 sourceKey 的 PERF_EXPECT 事实配对（与实收详情同口径）。
 */
@Data
@NoArgsConstructor
public class CommissionItemDetailVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 结佣明细 ID */
    private Long itemId;

    /** 业绩事实 ID */
    private Long factId;

    /** 员工 ID */
    private Long employeeId;

    /** 工号 */
    private String employeeCode;

    /** 姓名 */
    private String employeeName;

    /** 门店/组别（「集团-门店-组别」，与业绩明细页 deptPath 同口径） */
    private String deptPath;

    /** 归属部门 ID（调整详情回填部门名用） */
    private Long deptId;

    /** 角色类型 code（归一化优先） */
    private String roleType;

    /** 角色名称（原始录入） */
    private String roleName;

    /** 角色占比 */
    private BigDecimal shareRatio;

    /** 业务类型（结佣明细自带，取折算因子的键） */
    private String bizType;

    /** 应收金额（同 sourceKey 的 PERF_EXPECT 事实金额） */
    private BigDecimal expectedAmount;

    /** 新签月份（应收对应的新签事实归属月，如 2026-06、2026-07；跨月汇总时逗号分隔） */
    private String expectPeriod;

    /** 应收已被调整（同 sourceKey 存在 REVERSED 的 PERF_EXPECT 事实） */
    private Boolean expectedAdjusted;

    /**
     * 是否为「增加角色人」(ADD_MEMBER) 产生的新人明细行
     * （关联事实 source=MANUAL 且 sourceKey 带 MANUAL-ADJ/MANUAL-CADJ 标记）。
     * 新人行调整前新签业绩为 0，前端展示「新增角色人」标记与 0 → X。
     */
    private Boolean manualAdjust;

    /** 调整前应收金额（同 sourceKey 最早一条 REVERSED 的 PERF_EXPECT；无调整时回退当前值） */
    private BigDecimal originalExpectedAmount;

    /** 结佣金额（结佣明细确认的业绩金额） */
    private BigDecimal amount;

    /** 应收业绩折算后金额（expectedAmount × conversionFactor） */
    private BigDecimal expectedConvertedAmount;

    /** 调整前应收的折算后金额（originalExpectedAmount × conversionFactor） */
    private BigDecimal originalConvertedAmount;

    /** 结佣业绩折算后金额（amount × conversionFactor） */
    private BigDecimal convertedAmount;

    /** 费用项 */
    private String feeItem;

    /** 状态 DRAFT/PENDING/APPROVED/REVERSED */
    private String status;

    /** 实收金额（同合同+员工+角色 当前 ACTIVE 实收明细金额合计） */
    private BigDecimal receivedAmount;

    /** 实收金额（调整前：配对 rd 行 sourceKey 链最早 REVERSED 金额，实收加人新人行按 0；未调整时 = receivedAmount） */
    private BigDecimal originalReceivedAmount;
}
