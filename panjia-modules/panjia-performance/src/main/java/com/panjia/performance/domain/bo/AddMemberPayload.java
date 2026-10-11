package com.panjia.performance.domain.bo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 合同级调整单快照（payload_json 存储结构；ADD_MEMBER 与合同级 AMOUNT 指定值模式共用）。
 * <p>
 * ADD_MEMBER：新角色人信息 + 指定扣除清单（执行权威依据）+ 完整分摊预演快照（展示用）。
 * 执行时以 deductions 为准精确扣除，剩余部分按执行时当前金额重新等比分摊，
 * 默认保证合同总额不变（Σ执行后 = Σ执行前）。
 * <p>
 * 指定值模式（2026-09-28 可编辑表格交互）：{@link #detailTargets} 非空时，
 * 既有行按指定「调整后金额/角色占比」精确落库，AMOUNT 模式不再等比分摊；
 * ADD_MEMBER 模式下默认 Σtargets + 新人金额 = 合同总额（不变），
 * 自 2026-09-29 起允许同时调整合同总额（{@link #afterTotal} ≠ {@link #contractTotal}），
 * 审批前由前端二次确认。
 */
@Data
@NoArgsConstructor
public class AddMemberPayload implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 新角色人员工 ID */
    private Long newEmployeeId;

    /** 新角色人工号 */
    private String employeeCode;

    /** 新角色人姓名 */
    private String employeeName;

    /** 业绩归属部门 ID（默认员工档案部门） */
    private Long deptId;

    /** 部门名称（详情虚拟行展示用） */
    private String deptName;

    /** 角色类型（自由文本） */
    private String roleType;

    /** 新角色人业绩金额 X */
    private BigDecimal amount;

    /** 新角色人业绩比例（角色占比；可空=不设置占比；>0，不强制合计=100%） */
    private BigDecimal newShareRatio;

    /**
     * 新角色人清单（2026-10-11 起支持一张单添加多人）。
     * <p>新快照以此为权威列表；为空表示 2026-10-11 前的旧快照（仅一个新人，
     * 字段在顶层），读取统一走 {@link #effectiveMembers()} 回退。
     * 新快照顶层单新人字段仍写第一个新人，兼容结佣镜像等历史读取方。
     */
    private List<NewMember> members;

    /** 发起时合同业绩合计快照（调整前） */
    private BigDecimal contractTotal;

    /** 调整后合同业绩合计（= Σ既有行目标金额 + 新人金额；等于 contractTotal 时即总额不变） */
    private BigDecimal afterTotal;

    /** 指定扣除清单：优先从指定既有角色人身上精确扣除（执行权威依据） */
    private List<AdjustDeductionBo> deductions;

    /** 发起时完整分摊预演快照（展示用；执行时等比部分按当时金额重算） */
    private List<Alloc> allocations;

    /** 指定值模式：既有行「调整后金额/角色占比」清单（执行权威依据；非空时优先于 deductions 等比逻辑） */
    private List<AdjustDetailTargetBo> detailTargets;

    /**
     * 单条既有事实的分摊预演。
     */
    @Data
    @NoArgsConstructor
    public static class Alloc implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 事实 ID */
        private Long factId;

        /** 员工 ID */
        private Long employeeId;

        /** 员工姓名 */
        private String employeeName;

        /** 调整前金额 */
        private BigDecimal before;

        /** 变动额（负=扣减） */
        private BigDecimal delta;
    }

    /**
     * 单个新角色人快照（多人调整单的权威列表项）。
     */
    @Data
    @NoArgsConstructor
    public static class NewMember implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 新角色人员工 ID */
        private Long employeeId;

        /** 新角色人工号 */
        private String employeeCode;

        /** 新角色人姓名 */
        private String employeeName;

        /** 业绩归属部门 ID（默认员工档案部门） */
        private Long deptId;

        /** 部门名称（详情/在途虚拟行展示用） */
        private String deptName;

        /** 角色类型（自由文本） */
        private String roleType;

        /** 该新角色人业绩金额 */
        private BigDecimal amount;

        /** 该新角色人业绩比例（角色占比；可空=不设置占比） */
        private BigDecimal newShareRatio;
    }

    /**
     * 有效新角色人清单（统一读取入口）：{@link #members} 优先（多人新快照）；
     * 为空时回退顶层单新人字段（2026-10-11 前旧快照/结佣镜像单）。
     *
     * @return 不可为空的新角色人列表（可能为空列表，表示快照无新人）
     */
    public List<NewMember> effectiveMembers() {
        if (members != null && !members.isEmpty()) {
            return members;
        }
        if (newEmployeeId == null) {
            return List.of();
        }
        NewMember m = new NewMember();
        m.setEmployeeId(newEmployeeId);
        m.setEmployeeCode(employeeCode);
        m.setEmployeeName(employeeName);
        m.setDeptId(deptId);
        m.setDeptName(deptName);
        m.setRoleType(roleType);
        m.setAmount(amount);
        m.setNewShareRatio(newShareRatio);
        List<NewMember> fallback = new ArrayList<>(1);
        fallback.add(m);
        return fallback;
    }
}
