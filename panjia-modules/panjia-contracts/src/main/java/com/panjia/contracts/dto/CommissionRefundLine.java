package com.panjia.contracts.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 退单冲减结佣明细行（业绩域 → 结佣域）。
 * <p>
 * 退单执行时，业绩域已生成负新签事实；结佣域按「原事实已结佣明细」镜像生成
 * 同额负结佣明细（amount 取负，绑定负新签事实），使退单月份的算薪直接按负值计提成。
 */
@Data
public class CommissionRefundLine {

    /** 负新签事实 ID（结佣明细绑定该事实，保证后续发起结佣不会重复抵扣） */
    private Long performanceFactId;

    /** 员工 ID */
    private Long employeeId;

    /** 归属门店 ID */
    private Long deptId;

    /** 员工工号 */
    private String employeeCode;

    /** 合同号 */
    private String contractNo;

    /** 订单号 */
    private String orderNo;

    /** 业务类型 */
    private String bizType;

    /** 角色类型 */
    private String roleType;

    /** 角色名称 */
    private String roleName;

    /** 费用项 */
    private String feeItem;

    /** 占比 */
    private BigDecimal shareRatio;

    /** 冲减结佣业绩金额（负数） */
    private BigDecimal amount;
}
