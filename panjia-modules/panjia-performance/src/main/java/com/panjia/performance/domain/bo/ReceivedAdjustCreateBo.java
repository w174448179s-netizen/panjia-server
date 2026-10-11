package com.panjia.performance.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 实收调整创建请求（pj_perf_received_adjust，独立于新签调整）。
 * <p>
 * 口径固定：合同级 + PERF_REAL。录入调整后实收合计（targetAmount），
 * 后端按当前实收金额占比等比分摊到各明细行，审批通过后执行。
 */
@Data
public class ReceivedAdjustCreateBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 实收归属期间 YYYY-MM（必填） */
    @NotBlank(message = "实收期间不能为空")
    private String period;

    /** 合同号（必填） */
    @NotBlank(message = "合同号不能为空")
    private String contractNo;

    /** 订单号（可空；同合同号多订单时双键精确匹配） */
    private String orderNo;

    /** 调整后实收合计（优先；与 deltaAmount 二选一） */
    private BigDecimal targetAmount;

    /** 调整变动额（兼容：targetAmount 为空时 原合计 + deltaAmount；正=多收，负=少收） */
    private BigDecimal deltaAmount;

    /** 调整原因 */
    private String reason;

    /** 员工 ID（可空，后端从首条实收明细回填） */
    private Long employeeId;

    /** 部门 ID（可空，后端从首条实收明细回填） */
    private Long deptId;

    /** 业务类型（可空，后端从首条实收明细回填；号源口径用） */
    private String bizType;

    /** 分摊快照 JSON（后端 prepare 生成，前端传入会被覆盖） */
    private String payloadJson;
}
