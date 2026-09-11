package com.panjia.performance.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 归一化记录 DTO。
 * <p>
 * 用于跨域读取 import 域归一化后的业务记录，是业绩事实生成的数据来源。
 */
@Data
@NoArgsConstructor
public class NormalizedRecordDTO {

    /** 归一化记录ID */
    private Long id;

    /** 批次ID */
    private Long batchId;

    /** 来源类型 */
    private String sourceType;

    /** 业务发生日 */
    private LocalDate businessDate;

    /** 归属期间 */
    private String period;

    /** 员工工号 */
    private String employeeCode;

    /** 员工姓名 */
    private String employeeName;

    /** 部门全路径 */
    private String deptFullName;

    /** 业务类型 */
    private String bizType;

    /** 来源单号 */
    private String sourceKey;

    /** 原始金额 */
    private BigDecimal originAmount;

    /** 分摊比例 */
    private BigDecimal shareRatio;

    /** 扩展字段JSON */
    private String extJson;
}
