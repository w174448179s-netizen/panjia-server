package com.panjia.performance.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 实收审批单详情·每人明细行（实收明细页详情弹窗展示用）。
 * <p>
 * 列口径对齐「合同业绩明细」页：门店/组别、工号、姓名、所属角色、角色占比、应收金额、实收金额。
 * 应收金额按同 sourceKey 的 PERF_EXPECT 事实配对（导入引擎一行双发 REAL+EXPECT，
 * 与 {@code ReceivedAlignmentService} 配对口径一致）。
 */
@Data
@NoArgsConstructor
public class ReceivedFactDetailDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实收事实 ID（pj_perf_fact.id，fact_type=PERF_REAL） */
    private Long factId;

    /** 员工 ID */
    private Long employeeId;

    /** 工号 */
    private String employeeCode;

    /** 姓名 */
    private String employeeName;

    /** 门店/组别（「集团-门店-组别」，与业绩明细页 deptPath 同口径） */
    private String deptPath;

    /** 角色类型 code（归一化优先） */
    private String roleType;

    /** 角色名称（原始录入） */
    private String roleName;

    /** 角色占比 */
    private BigDecimal shareRatio;

    /** 应收金额（同 sourceKey 的 PERF_EXPECT 事实金额） */
    private BigDecimal expectedAmount;

    /** 实收金额（PERF_REAL 事实金额） */
    private BigDecimal amount;
}
