package com.panjia.performance.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 导入批次按合同聚合的实收业绩组（实收审批单自动建单中间结果）。
 */
@Data
public class ReceivedContractGroupDTO {

    /** 合同号 */
    private String contractNo;

    /** 订单号 */
    private String orderNo;

    /** 物业地址 */
    private String propertyAddress;

    /** 签约/业务时间 */
    private LocalDateTime businessDate;

    /** 实收业绩合计 */
    private BigDecimal receivedAmount;

    /** 同合同应收业绩合计（PERF_EXPECT） */
    private BigDecimal expectedAmount;

    /** 明细条数 */
    private Integer itemCount;
}
