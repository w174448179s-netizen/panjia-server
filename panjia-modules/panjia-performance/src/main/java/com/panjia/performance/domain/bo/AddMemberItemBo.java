package com.panjia.performance.domain.bo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 增加角色人调整单的单个新角色人（2026-10-11 起支持一张调整单添加多人）。
 * <p>
 * 合同业绩调整弹窗切到「增加角色人」后可连续添加多行新人，每行选择员工、
 * 角色类型、业绩金额与可选角色占比，随既有行调整一次提交审批。
 */
@Data
@NoArgsConstructor
public class AddMemberItemBo {

    /** 新角色人员工 ID（必填，须不在该合同既有有效事实中，且同一张单内不可重复） */
    private Long employeeId;

    /** 新角色人角色类型（如"合作人"，自由文本；空默认"合作人"） */
    private String roleType;

    /** 新角色人业绩金额（&gt;0；所有新人金额合计参与扣除分摊/合同总额校验） */
    private BigDecimal amount;

    /** 新角色人业绩比例/角色占比（可空=不设置；允许 0，负数非法；不强制各角色合计=100%） */
    private BigDecimal shareRatio;

    /** 新角色人业绩归属部门（空=取员工档案部门） */
    private Long deptId;
}
