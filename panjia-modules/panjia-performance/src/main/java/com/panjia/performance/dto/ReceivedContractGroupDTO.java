package com.panjia.performance.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 导入批次按订单号聚合的实收业绩组（实收审批单自动建单中间结果）。
 * <p>
 * 聚合维度 = 订单号：贝壳原始行中 order_no 与 contract_no 严格 1:1，
 * 故按订单号聚合与按业务类型取键（旧 CASE 口径）完全等价。
 */
@Data
public class ReceivedContractGroupDTO {

    /** 合同号（同组快照，1:1 对应 orderNo） */
    private String contractNo;

    /** 订单号（聚合维度 = 业务键） */
    private String orderNo;

    /** 业务类型（一手房/二手买卖/租赁…，建单时落库到审批单） */
    private String bizType;

    /** 物业地址 */
    private String propertyAddress;

    /** 签约/业务时间 */
    private LocalDateTime businessDate;

    /** 实收业绩合计 */
    private BigDecimal receivedAmount;

    /** 明细条数（含金额为 0 的行） */
    private Integer itemCount;
}
