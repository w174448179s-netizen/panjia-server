package com.panjia.commission.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 发起结佣请求 DTO（结算月 + 合同号）。
 * <p>
 * 申请单粒度 = 合同 + 月，一张申请单只含该合同当月的实收业绩明细。
 * 批量发起（/batch）时 contractNo 不传、deptId 可选（按门店范围批量）。
 */
@Data
public class ApplyCreateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业绩归属月（结算月 YYYY-MM） */
    @NotBlank(message = "结算月不能为空")
    private String period;

    /** 合同号（单个发起必填；批量发起不传） */
    private String contractNo;

    /** 门店 ID（仅批量发起使用，null=全部门店；非 null 含下级） */
    private Long deptId;
}
