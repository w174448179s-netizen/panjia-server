package com.panjia.performance.domain.bo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 业绩冲正/补录批量录入参数。
 * 针对一个合同在指定期间录入多条人员记录。
 */
@Data
public class ManualOffsetBo {
    @NotBlank(message = "合同号不能为空")
    private String contractNo;
    /** 订单号（同合同号挂多订单时与合同号双键精确匹配模板事实，防止串单；可空退化旧口径） */
    private String orderNo;
    @NotBlank(message = "期间不能为空")
    private String period;
    @NotEmpty(message = "冲正明细不能为空")
    @Valid
    private List<ManualOffsetItem> items;
    private String reason;
}
