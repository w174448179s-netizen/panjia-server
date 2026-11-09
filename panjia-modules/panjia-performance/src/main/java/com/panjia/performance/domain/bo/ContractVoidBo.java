package com.panjia.performance.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 合同级作废/恢复操作参数。
 */
@Data
@NoArgsConstructor
public class ContractVoidBo {

    /** 归属期间 YYYY-MM（作废/恢复时用于定位事实的原期间） */
    @NotBlank(message = "期间不能为空")
    private String period;

    /**
     * 恢复后落入的目标期间 YYYY-MM（仅恢复使用；为空时默认当前月）。
     * <p>
     * 用于「8 月作废、恢复时落到 9 月」的场景；指定后事实的 period 与 source_key
     * 会同步改写到目标月，保证目标月后续导入仍按业务键幂等。
     */
    @Pattern(regexp = "\\d{4}-\\d{2}", message = "恢复月份格式必须为 YYYY-MM")
    private String targetPeriod;

    /** 事实口径 */
    @NotBlank(message = "事实口径不能为空")
    private String factType;

    /** 合同号（或订单号） */
    @NotBlank(message = "合同号不能为空")
    private String contractNo;

    /** 订单号（同合同号挂多订单时与合同号双键精确匹配，防止跨订单作废/恢复；可空退化旧口径） */
    private String orderNo;

    /** 作废/恢复原因 */
    @NotBlank(message = "原因不能为空")
    private String reason;
}
