package com.panjia.contracts.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 业绩「合同」维度摘要（performance 域对外契约 DTO）。
 * <p>
 * 按合同号聚合的业绩事实汇总，供结佣域按合同发起 / 列表合并使用；
 * 金额为业绩域原样值合计，消费方不得二次折算。
 */
@Data
public class PerformanceContractSummaryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 合同号（聚合键） */
    private String contractNo;

    /** 订单号（一手房展示用） */
    private String orderNo;

    /** 业务类型 */
    private String bizType;

    /** 房源地址 */
    private String propertyAddress;

    /** 签约/认购时间 */
    private LocalDateTime businessDate;

    /** 合同业绩金额合计（事实原样值求和） */
    private BigDecimal amount;

    /** 应收业绩合计（PERF_REAL 查询时附带的同合同 PERF_EXPECT 合计，§3.4 差异判定用；可能为 null） */
    private BigDecimal expectedAmount;

    /**
     * 实收审批状态（PERF_REAL 查询时返回）：
     * 全部实收事实已挂 APPROVED 实收单 → APPROVED；存在未完结单 → SUBMITTED/DRAFT；
     * 无任何实收单 → null。
     */
    private String receivedStatus;

    /** 涉及签约人数（去重） */
    private long employeeCount;

    /** 明细条数 */
    private long detailCount;
}
