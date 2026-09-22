package com.panjia.performance.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 业绩调整单·受影响的明细行。
 * <p>
 * 字段口径对齐实收详情的 ReceivedFactDetailVo，
 * 额外增加「调整前金额」「调整后金额」便于审批人直观对比。
 */
@Data
@NoArgsConstructor
public class AdjustFactDetailVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事实 ID */
    private Long factId;

    /** 员工 ID */
    private Long employeeId;

    /** 工号 */
    private String employeeCode;

    /** 姓名 */
    private String employeeName;

    /** 门店/组别 */
    private String deptPath;

    /** 角色类型 code */
    private String roleType;

    /** 角色名称 */
    private String roleName;

    /** 角色占比 */
    private BigDecimal shareRatio;

    /** 应收金额 */
    private BigDecimal expectedAmount;

    /** 当前金额（调整前的 performance_amount） */
    private BigDecimal amount;

    /** 调整后金额 */
    private BigDecimal afterAmount;

    /** 变动金额 */
    private BigDecimal deltaAmount;

    /** 是否是本次调整的直接目标行（明细级=是，合同级=全部都是） */
    private Boolean target;

    /** 事实状态（ACTIVE / SUPERSEDED / REVERSED 等），用于过滤已冲销行 */
    private String factStatus;

    /** 折算后当前金额（amount × conversionFactor） */
    private BigDecimal convertedAmount;

    /** 折算后调整后金额（afterAmount × conversionFactor） */
    private BigDecimal convertedAfterAmount;
}
