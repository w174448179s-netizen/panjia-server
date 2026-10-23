package com.panjia.performance.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 退单创建请求（pj_perf_refund）。
 */
@Data
public class PerfRefundCreateBo {

    /** 合同号（必填） */
    @NotBlank(message = "合同号不能为空")
    private String contractNo;

    /** 订单号（可空；同合同多订单时精确冲退） */
    private String orderNo;

    /** 退单落账期间 YYYY-MM（必填；负流水归属月，不得填已封账月份） */
    @NotBlank(message = "退单月份不能为空")
    private String refundPeriod;

    /** 退单原因（必填） */
    @NotBlank(message = "退单原因不能为空")
    private String reason;
}
