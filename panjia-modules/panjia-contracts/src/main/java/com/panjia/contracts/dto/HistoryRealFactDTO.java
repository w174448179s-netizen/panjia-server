package com.panjia.contracts.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 历史导入批次实收事实明细（结佣域 LOCKED 建单用）。
 * <p>
 * 由业绩域经 {@code CommissionPerformanceQueryPort#listRealFactsByBatch} 提供：
 * 指定历史批次下 factType=PERF_REAL 且 ACTIVE 的事实行，字段覆盖老导入器
 * ensureCommissionApplications 的建单/建明细所需列。
 */
@Data
public class HistoryRealFactDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业绩事实 ID（pj_commission_item.performance_fact_id） */
    private Long factId;

    /** 订单号 */
    private String orderNo;

    /** 合同号 */
    private String contractNo;

    /** sourceKey（订单号/合同号均为空时的业务键兜底） */
    private String sourceKey;

    /** 物业地址 */
    private String propertyAddress;

    /** 业务日期（签约/成销日） */
    private LocalDate businessDate;

    /** 业务类型 */
    private String bizType;

    /** 部门 ID */
    private Long deptId;

    /** 员工 ID */
    private Long employeeId;

    /** 角色类型 */
    private String roleType;

    /** 费用项 */
    private String feeItem;

    /** 业绩金额（performance_amount） */
    private BigDecimal amount;
}
