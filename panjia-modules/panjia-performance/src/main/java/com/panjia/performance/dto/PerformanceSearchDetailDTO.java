package com.panjia.performance.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 完整业绩查询·合同下明细 DTO（按签约人 + 角色一行）。
 * <p>
 * 供业绩查询页点击合同号/订单号后的「查看详情」弹窗使用。
 * 以 PERF_EXPECT（新签/应收）ACTIVE 事实为基准行，按 source_key 配对同业务的
 * PERF_REAL（实收）金额，一行同时展示应收/实收双口径。
 */
@Data
public class PerformanceSearchDetailDTO {

    /** 应收事实 ID（行主键） */
    private Long factId;

    /** 归属期间（明细含该业务键全部期间，与列表的合同全周期聚合口径一致） */
    private String period;

    /** 员工 ID */
    private Long employeeId;

    /** 工号 */
    private String employeeCode;

    /** 姓名 */
    private String employeeName;

    /** 门店/组别全路径：大区-门店-组（组与门店同名时只到两级） */
    private String deptPath;

    /** 所属角色 */
    private String roleType;

    /** 角色名（导入原值） */
    private String roleName;

    /** 角色占比 */
    private BigDecimal shareRatio;

    /** 签约/认购日期 */
    private LocalDateTime businessDate;

    /** 应收金额（新签业绩，PERF_EXPECT，调整后） */
    private BigDecimal expectAmount;

    /** 应收原始金额（调整前；未调整时 = expectAmount） */
    private BigDecimal originalExpectAmount;

    /** 实收金额（PERF_REAL，按 source_key 配对；无实收时为 0） */
    private BigDecimal realAmount;

    /** 是否已结算（存在有效结佣明细） */
    private Boolean settled;

    /** 结算日期 */
    private LocalDateTime settleDate;
}
