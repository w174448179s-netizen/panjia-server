package com.panjia.contracts.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实收合同信息跨域查询结果（contracts 端口 DTO）。
 * <p>
 * 数据源：pj_received_contract（实收合同主表）。
 * 用途：业绩域在「实收 DRAFT、pj_perf_fact 尚无 ACTIVE 事实」场景下
 * 补全合同展示信息（订单号/合同号/签约时间/房源地址），
 * 如新签补录调整单的审批详情页。
 */
@Data
public class ReceivedContractInfoDTO {

    /** 订单号 */
    private String orderNo;

    /** 合同号 */
    private String contractNo;

    /** 业务日期（成交日/到账日） */
    private LocalDateTime businessDate;

    /** 房源地址 */
    private String propertyAddress;
}
