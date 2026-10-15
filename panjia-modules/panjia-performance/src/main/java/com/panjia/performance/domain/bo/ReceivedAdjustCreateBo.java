package com.panjia.performance.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 实收调整创建请求（pj_perf_received_adjust，独立于新签调整）。
 * <p>
 * 口径固定：合同级 + PERF_REAL。2026-10-14 起与新签调整对齐三类型：
 * AMOUNT=业绩调整（总额不变）/ DEDUCT=业绩扣减（总额减少）/ INCREASE=业绩增加（总额增加），
 * 三类均支持明细指定值 {@link #detailTargets} 与增加角色人 {@link #newMembers}；
 * 不传明细指定值时保留旧链路（按 targetAmount/deltaAmount 等比分摊，类型按 AMOUNT）。
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

    /**
     * 调整类型：AMOUNT=业绩调整 / DEDUCT=业绩扣减 / INCREASE=业绩增加（可空，默认 AMOUNT）。
     */
    private String adjustType;

    /** 调整后实收合计（明细指定值模式由后端按 Σ目标行+新人 回填；等比模式优先于 deltaAmount） */
    private BigDecimal targetAmount;

    /** 调整变动额（旧等比链路：targetAmount 为空时 原合计 + deltaAmount；正=多收，负=少收） */
    private BigDecimal deltaAmount;

    /** 调整原因 */
    private String reason;

    /** 员工 ID（可空，后端从首条实收明细回填） */
    private Long employeeId;

    /** 部门 ID（可空，后端从首条实收明细回填） */
    private Long deptId;

    /** 业务类型（可空，后端从首条实收明细回填；号源口径用） */
    private String bizType;

    /** 明细指定值（可编辑表格，三类型统一）：按行指定调整后金额/角色占比，为空时走旧等比分摊 */
    private List<AdjustDetailTargetBo> detailTargets;

    /** 新角色人清单（三类型均可加人；可空） */
    private List<AddMemberItemBo> newMembers;

    /** 分摊快照 JSON（后端 prepare 生成，前端传入会被覆盖） */
    private String payloadJson;
}
