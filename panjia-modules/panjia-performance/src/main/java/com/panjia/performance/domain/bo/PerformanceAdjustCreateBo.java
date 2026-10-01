package com.panjia.performance.domain.bo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 业绩调整单创建请求。
 */
@Data
@NoArgsConstructor
public class PerformanceAdjustCreateBo {

    /** 关联业绩事实 ID */
    private Long factId;

    /** 归属期间 YYYY-MM */
    private String period;

    /** 员工 ID */
    private Long employeeId;

    /** 原部门 ID */
    private Long deptId;

    /** 调整类型：AMOUNT / VOID / TRANSFER / ADD_MEMBER */
    private String adjustType;

    /** 调整范围：CONTRACT-合同级 / DETAIL-明细级 */
    private String adjustScope;

    /** 合同号（合同级调整时必填，用于定位该合同下全部明细） */
    private String contractNo;

    /** 事实口径：仅允许 PERF_EXPECT（§4.1 业绩调整只改应收） */
    private String factType;

    /**
     * 原业绩归属月 YYYY-MM（合同级跨月调整时必填，用于定位原月事实；
     * 为空则视 period 为原月，即同月调整）。§4.6 跨月调整走业绩冲销。
     */
    private String originalPeriod;

    /** 金额变动值（金额调整时使用，自动计算：targetAmount - 当前金额） */
    private BigDecimal deltaAmount;

    /** 目标金额（金额调整时使用，用户输入的调整后金额） */
    private BigDecimal targetAmount;

    /** 调整原因 */
    private String reason;

    /** 调整详情 JSON（扩展字段） */
    private String payloadJson;

    // ==================== 增加角色人（ADD_MEMBER，2026-09-28） ====================

    /** 新角色人员工 ID（增加角色人时必填，须不在该合同既有有效事实中） */
    private Long newEmployeeId;

    /** 新角色人角色类型（如"合作人"，自由文本） */
    private String newRoleType;

    /** 新角色人业绩金额（>0；总额不变场景下须 ≤ 合同当前业绩合计） */
    private BigDecimal newAmount;

    /** 新角色人业绩比例/角色占比（可空=不设置；>0，不强制各角色合计=100%） */
    private BigDecimal newShareRatio;

    /** 新角色人业绩归属部门（默认取员工档案部门，可改） */
    private Long newDeptId;

    /** 指定扣除清单：优先从指定既有角色人身上精确扣除，剩余由未指定行按占比等比分摊 */
    private List<AdjustDeductionBo> deductions;

    // ==================== 可编辑表格指定值模式（2026-09-28） ====================

    /**
     * 明细指定值清单：调整弹窗可编辑表格按行提交「调整后金额/角色占比」，
     * 审批通过后按指定值精确落库。合同级 AMOUNT（金额调整）与 ADD_MEMBER（增加角色人）均支持；
     * 为空时保持旧交互（AMOUNT 等比分摊 / ADD_MEMBER deductions 扣除）。
     */
    private List<AdjustDetailTargetBo> detailTargets;
}
