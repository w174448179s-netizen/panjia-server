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

    /** 来源类型（ImportSourceType code：KE_SIGNED / KE_NEW_SIGN / ATTENDANCE / POINTS / OTHERS） */
    private String sourceType;

    /**
     * 归一化记录类型（NormalizedRecordType code：SIGNED / NEW_SIGN / ATTENDANCE / POINTS / MANUAL）。
     * <p>
     * 业绩双口径分发的权威依据：SIGNED 双发 PERF_REAL+PERF_EXPECT，NEW_SIGN 单发 PERF_EXPECT。
     */
    private String recordType;

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

    /**
     * 原始金额（兼容单口径消费方的默认金额）。
     * <p>
     * 取值：SIGNED 行=当月实收（PERF_REAL 口径），NEW_SIGN 行=当月应收（PERF_EXPECT 口径）。
     * 双口径消费方应直接使用 {@link #receivedAmount} / {@link #receivableAmount}，勿依赖本字段猜口径。
     */
    private BigDecimal originAmount;

    /** 当月应收金额（PERF_EXPECT 新签业绩口径，SIGNED / NEW_SIGN 行均有值） */
    private BigDecimal receivableAmount;

    /** 当月实收金额（PERF_REAL 结佣计薪业绩口径，SIGNED 行有值；NEW_SIGN 批次可能为空） */
    private BigDecimal receivedAmount;

    /** 分摊比例 */
    private BigDecimal shareRatio;

    /** 角色类型 */
    private String roleType;

    /** 扩展字段JSON */
    private String extJson;
}