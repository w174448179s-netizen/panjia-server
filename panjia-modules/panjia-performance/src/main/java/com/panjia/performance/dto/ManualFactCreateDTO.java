package com.panjia.performance.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 手工录入业绩事实请求。
 * <p>
 * 用于人工补录单笔业绩事实，适用于系统外业务或遗漏数据的补登场景。
 */
@Data
@NoArgsConstructor
public class ManualFactCreateDTO {

    /** 事实口径 */
    private String factType;

    /** 归属期间 YYYY-MM */
    private String period;

    /** 业务发生日 */
    private LocalDate businessDate;

    /** 员工 ID */
    private Long employeeId;

    /** 员工工号 */
    private String employeeCode;

    /** 部门 ID */
    private Long deptId;

    /** 角色类型 */
    private String roleType;

    /** 业务类型 */
    private String bizType;

    /** 来源单号 */
    private String sourceKey;

    /** 分摊比例 */
    private BigDecimal shareRatio;

    /** 原始金额 */
    private BigDecimal originAmount;

    /** 录入原因 */
    private String reason;
}
