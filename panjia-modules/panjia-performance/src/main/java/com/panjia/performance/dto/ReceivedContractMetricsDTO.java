package com.panjia.performance.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 实收明细列表·合同维度补充字段（列表页展示用，不入库）。
 * <p>
 * 实收审批单（{@code pj_perf_received_apply}）本身不存业务类型与涉及人数，
 * 列表查询后按 (period, contractNo) 从 ACTIVE PERF_REAL 事实回填，
 * 口径与「实收审批单详情·每人实收明细」保持一致。
 */
@Data
@NoArgsConstructor
public class ReceivedContractMetricsDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 合同号 */
    private String contractNo;

    /** 业务类型（同合同多事实时取 MAX） */
    private String bizType;

    /** 涉及人数（该合同本期间实收事实的去重员工数） */
    private Integer employeeCount;

    /** 应收业绩合计（该合同本期间 ACTIVE PERF_EXPECT 事实合计，含已生效调整） */
    private BigDecimal expectedAmount;
}
