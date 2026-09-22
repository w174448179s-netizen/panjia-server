package com.panjia.performance.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 业绩查询合同明细查询条件。
 */
@Data
@NoArgsConstructor
public class PerformanceSearchDetailBo {

    /** 业务键（合同号或订单号） */
    @NotBlank(message = "业务键不能为空")
    private String bizNo;
}
