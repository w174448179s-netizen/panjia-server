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

    /** 涉及签约人数（去重） */
    private long employeeCount;

    /** 明细条数 */
    private long detailCount;
}
