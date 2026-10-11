package com.panjia.commission.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 发起结佣请求 DTO（结算月 + 合同号 + 可选订单号）。
 * <p>
 * 申请单粒度 = 订单 + 月（同一合同号可挂多个订单号，如房产金融）。
 * 列表单个发起时合同号、订单号都传，后台以订单号为准精确装载该订单实收/新签事实；
 * orderNo 为空（历史入口/手工调用）时退合同号口径，由多订单防串单校验兜底。
 * 批量发起走独立接口（/batch-apply-by-contract），合同号自动展开其下全部订单。
 */
@Data
public class CommissionApplyCreateBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业绩归属月（结算月 YYYY-MM） */
    @NotBlank(message = "结算月不能为空")
    private String period;

    /** 合同号（必填；快照归一化与新签双键匹配用） */
    @NotBlank(message = "合同号不能为空")
    private String contractNo;

    /** 订单号（列表单个发起必传：以订单号为准，同合同多订单互不串单；历史入口可空） */
    private String orderNo;

    /** 门店 ID（仅批量发起使用，null=全部门店；非 null 含下级） */
    private Long deptId;
}
