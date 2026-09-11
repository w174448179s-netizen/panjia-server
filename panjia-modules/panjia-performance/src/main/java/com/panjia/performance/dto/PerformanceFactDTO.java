package com.panjia.performance.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 业绩事实 DTO。
 * <p>
 * 对外（佣金域等）暴露的业绩事实只读模型，包含事实口径、期间、员工、金额等核心字段。
 */
@Data
@NoArgsConstructor
public class PerformanceFactDTO {

    /** 事实ID */
    private Long id;

    /** 事实口径 */
    private String factType;

    /** 归属期间 */
    private String period;

    /** 业务发生日 */
    private LocalDate businessDate;

    /** 员工ID */
    private Long employeeId;

    /** 员工工号 */
    private String employeeCode;

    /** 员工姓名 */
    private String employeeName;

    /** 部门ID */
    private Long deptId;

    /** 部门名称 */
    private String deptName;

    /** 业务类型 */
    private String bizType;

    /** 来源单号 */
    private String sourceKey;

    /** 分摊比例 */
    private BigDecimal shareRatio;

    /** 原始金额 */
    private BigDecimal originAmount;

    /** 折算系数 */
    private BigDecimal conversionRate;

    /** 业绩金额 */
    private BigDecimal performanceAmount;

    /** 状态 */
    private String factStatus;

    /** 来源 */
    private String source;

    /** 创建时间 */
    private LocalDateTime createTime;
}
