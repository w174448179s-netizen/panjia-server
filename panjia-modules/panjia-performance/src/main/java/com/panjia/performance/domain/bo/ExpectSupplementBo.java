package com.panjia.performance.domain.bo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 新签业绩补录参数。
 * <p>
 * 场景：实收已入库但无新签（明细未绑定审批单，待新签匹配），需手工补录 PERF_EXPECT 事实。
 * 合同号、订单号、bizType、propertyAddress 等字段从实收继承；签约日期与新签金额手工录入。
 * 补录后自动触发实收补建单（resolvePendingAfterNewSign）。
 */
@Data
public class ExpectSupplementBo {
    /** 实收月（用于按订单/月补建审批单，从实收行继承） */
    @NotNull(message = "实收月不能为空")
    private String receivedPeriod;

    /** 新签结算月（PERF_EXPECT 事实归属期间，用户录入） */
    @NotNull(message = "新签结算月不能为空")
    private String period;

    /** 合同号（从实收继承） */
    private String contractNo;

    /** 订单号（从实收继承，部分业务无订单号则传 null） */
    private String orderNo;

    /** 签约时间（从实收行继承，含时分秒；可空时回退为新签结算月 1 号 00:00:00） */
    private LocalDateTime businessDate;

    /** 业务类型（从实收继承） */
    private String bizType;

    /** 房源地址（从实收继承） */
    private String propertyAddress;

    /** 门店 ID（从实收合同继承，作为各角色人默认门店） */
    private Long deptId;

    /** 费用项（从实收明细继承，可空） */
    private String feeItem;

    /** 补录明细（每角色人一条） */
    @NotEmpty(message = "补录明细不能为空")
    @Valid
    private List<ExpectSupplementItem> items;
}
