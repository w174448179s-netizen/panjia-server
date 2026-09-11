package com.panjia.contracts.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 归一化记录 DTO（跨域契约，panjia-contracts 叶子模块）。
 * <p>
 * 用于跨域读取 import 域归一化后的业务记录，是业绩事实生成的数据来源（§4.1 ⑤）。
 * <p>
 * payload 约束：基础类型 / Long / String / BigDecimal，禁止持有 @Entity。
 * <p>
 * 注：V2.0 归一化暂未存 {@code businessDate}（签约日），DTO 字段保留为可空，
 * 业绩域快照按 {@code period}（结算月）取数；V2.1 归一化落签约日后再补齐该字段。
 */
@Data
@NoArgsConstructor
public class NormalizedRecordDTO {

    /** 归一化记录ID */
    private Long id;

    /** 批次ID */
    private Long batchId;

    /** 来源类型（ImportSourceType code） */
    private String sourceType;

    /** 业务发生日（签约日；V2.0 暂为 null） */
    private LocalDate businessDate;

    /** 归属期间（结算月 YYYY-MM） */
    private String period;

    /** 员工工号 */
    private String employeeCode;

    /** 员工姓名（V2.0 暂为 null，待 PeopleSnapshotAdapter 接入后填充） */
    private String employeeName;

    /** 部门全路径（V2.0 暂为 null，待 PeopleSnapshotAdapter 接入后填充） */
    private String deptFullName;

    /** 业务类型 */
    private String bizType;

    /** 来源单号 */
    private String sourceKey;

    /** 原始金额 */
    private BigDecimal originAmount;

    /** 分摊比例 */
    private BigDecimal shareRatio;

    /** 角色类型 */
    private String roleType;

    /** 扩展字段JSON */
    private String extJson;
}