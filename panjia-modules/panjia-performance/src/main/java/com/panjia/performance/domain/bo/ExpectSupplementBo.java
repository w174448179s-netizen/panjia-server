package com.panjia.performance.domain.bo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 新签业绩补录参数。
 * <p>
 * 场景：实收审批单 DRAFT（待补新签）状态——有实收无新签，需手工补录 PERF_EXPECT 事实。
 * 合同号、订单号、bizType、propertyAddress 等字段从实收继承；签约日期与新签金额手工录入。
 * 补录后自动触发实收 DRAFT 单重评（resolveDraftAfterNewSign）。
 */
@Data
public class ExpectSupplementBo {
    /** 实收月（用于查 DRAFT 单触发重评，从实收行继承） */
    @NotNull(message = "实收月不能为空")
    private String receivedPeriod;

    /** 新签结算月（PERF_EXPECT 事实归属期间，用户录入） */
    @NotNull(message = "新签结算月不能为空")
    private String period;

    /** 合同号（从实收继承） */
    private String contractNo;

    /** 订单号（从实收继承，部分业务无订单号则传 null） */
    private String orderNo;

    /** 签约日期（手工录入） */
    @NotNull(message = "签约日期不能为空")
    private LocalDate businessDate;

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
