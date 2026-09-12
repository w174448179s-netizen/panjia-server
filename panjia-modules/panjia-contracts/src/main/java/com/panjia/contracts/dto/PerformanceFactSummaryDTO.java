package com.panjia.contracts.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 业绩事实摘要 DTO（跨域契约，panjia-contracts 叶子模块）。
 * <p>
 * 业绩域经 {@code CommissionPerformanceQueryPort} 对结佣域暴露的事实只读视图。
 * 只含结佣确认所需的最小字段集，不含折算系数 / 分摊比例等加工字段（CI C6/C7）。
 */
@Data
@NoArgsConstructor
public class PerformanceFactSummaryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事实 ID（pj_perf_fact.id） */
    private Long factId;

    /** 事实口径（FactType code：PERF_REAL / PERF_EXPECT） */
    private String factType;

    /** 事实状态（FactStatus code：ACTIVE / REVERSED） */
    private String factStatus;

    /** 归属期间 YYYY-MM */
    private String period;

    /** 业务发生日 */
    private LocalDate businessDate;

    /** 员工 ID */
    private Long employeeId;

    /** 员工工号 */
    private String employeeCode;

    /** 归属部门 ID（门店） */
    private Long deptId;

    /** 业务类型 */
    private String bizType;

    /** 角色类型 */
    private String roleType;

    /** 业绩金额（业绩域原样值，不折算） */
    private BigDecimal amount;

    /** 来源导入批次 ID */
    private Long batchId;

    /** 归一化记录 ID（溯源用） */
    private Long normalizedRecordId;

    /** 来源业务单号（幂等锚点，审计用） */
    private String sourceKey;
}
