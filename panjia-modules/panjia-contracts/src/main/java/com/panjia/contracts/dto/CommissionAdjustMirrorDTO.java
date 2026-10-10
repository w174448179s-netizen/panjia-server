package com.panjia.contracts.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 结佣调整执行后的新签调整单镜像（写入 pj_perf_adjust，status=EXECUTED，无审批流）。
 * <p>
 * 用途：结佣调整直接 supersede 业绩事实（PERF_EXPECT），但新签界面「原值 → 调整后值」展示
 * 依赖 pj_perf_adjust 调整单快照还原；执行结佣调整时同步登记一单已执行的新签调整单，
 * 使新签合同列表/明细页能展示调整前 → 调整后金额变化。
 * <p>
 * 口径：仅 AMOUNT / ADD_MEMBER 两类合同级（及明细级 AMOUNT）产生镜像；VOID / TRANSFER 不镜像。
 */
@Data
public class CommissionAdjustMirrorDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 调整单号（唯一；直接用结佣调整单号 CADJ...，幂等键） */
    private String adjustNo;

    /** 归属期间 YYYY-MM */
    private String period;

    /** 合同号 */
    private String contractNo;

    /** 订单号（可空；同合同号多订单时订单号优先精确匹配，镜像落 pj_perf_adjust.order_no） */
    private String orderNo;

    /** 调整范围：CONTRACT-合同级 / DETAIL-明细级 */
    private String adjustScope;

    /** 调整类型：AMOUNT / ADD_MEMBER */
    private String adjustType;

    /** 明细级镜像对应的新事实 ID（supersede 后的 ACTIVE 事实，供新签明细页按 factId 还原） */
    private Long factId;

    /** 调整前金额（合同级=合同合计；明细级=该明细调整前金额） */
    private BigDecimal originalAmount;

    /** 调整后金额（AMOUNT=目标值；ADD_MEMBER=新人金额 X，与新签侧口径一致） */
    private BigDecimal targetAmount;

    /** 调整原因 */
    private String reason;

    /** 发起人 ID */
    private Long applicantId;

    /** 审批人 ID（执行人） */
    private Long approverId;

    // ==================== ADD_MEMBER 专用 ====================

    /** 新角色人员工 ID */
    private Long newEmployeeId;

    /** 新角色人工号 */
    private String newEmployeeCode;

    /** 新角色人姓名 */
    private String newEmployeeName;

    /** 新角色人部门 ID */
    private Long newDeptId;

    /** 新角色人角色类型 */
    private String newRoleType;

    /** 新角色人角色占比 */
    private BigDecimal newShareRatio;

    /** 调整后合同合计（Σ既有行目标 + 新人金额） */
    private BigDecimal afterTotal;

    /** 既有行逐人分摊快照（还原用：新签明细页逆向回放 delta） */
    private List<Alloc> allocations;

    /** 单条既有事实的分摊快照 */
    @Data
    public static class Alloc implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        /** 调整前旧事实 ID（执行后已 REVERSED，新签侧按 sourceKey 映射回当前行） */
        private Long factId;

        /** 员工 ID */
        private Long employeeId;

        /** 员工姓名 */
        private String employeeName;

        /** 调整前金额 */
        private BigDecimal before;

        /** 变动额（调整后 − 调整前） */
        private BigDecimal delta;
    }
}
