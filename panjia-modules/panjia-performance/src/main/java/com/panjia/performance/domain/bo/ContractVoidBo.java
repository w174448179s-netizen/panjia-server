package com.panjia.performance.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 合同级作废/恢复操作参数。
 */
@Data
@NoArgsConstructor
public class ContractVoidBo {

    /** 归属期间 YYYY-MM */
    @NotBlank(message = "期间不能为空")
    private String period;

    /** 事实口径 */
    @NotBlank(message = "事实口径不能为空")
    private String factType;

    /** 合同号（或订单号） */
    @NotBlank(message = "合同号不能为空")
    private String contractNo;

    /** 作废/恢复原因 */
    @NotBlank(message = "原因不能为空")
    private String reason;
}
