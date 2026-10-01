package com.panjia.commission.domain.bo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 结佣调整单快照（payload_json 存储结构；合同级 AMOUNT 指定值模式与 ADD_MEMBER 共用）。
 * <p>
 * 镜像新签调整 AddMemberPayload：detailTargets 为执行权威依据（按 itemId 定位执行时明细，
 * supersede 其绑定的新签事实为指定「调整后金额/角色占比」）；
 * ADD_MEMBER 追加 newMember 新角色人信息，默认合同总额不变（Σ既有行目标 + 新人金额 = afterTotal）。
 */
@Data
@NoArgsConstructor
public class CommissionAdjustPayload implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 指定值模式：既有行「调整后金额/角色占比」清单（执行权威依据） */
    private List<DetailTarget> detailTargets;

    /** 增加角色人信息（ADD_MEMBER 用） */
    private NewMember newMember;

    /** 发起时明细合计快照（调整前） */
    private BigDecimal contractTotal;

    /** 调整后明细合计（AMOUNT=targetAmount；ADD_MEMBER=Σ既有行目标+新人金额） */
    private BigDecimal afterTotal;

    /** 既有行指定目标 */
    @Data
    @NoArgsConstructor
    public static class DetailTarget implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        /** 结佣明细 ID */
        private Long itemId;

        /** 调整后金额 */
        private BigDecimal targetAmount;

        /** 调整后角色占比（可空=不变） */
        private BigDecimal shareRatio;
    }

    /** 新角色人 */
    @Data
    @NoArgsConstructor
    public static class NewMember implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        /** 新员工 ID */
        private Long employeeId;

        /** 工号（发起时快照） */
        private String employeeCode;

        /** 姓名（发起时快照，详情展示用） */
        private String employeeName;

        /** 归属部门 ID（默认员工档案部门） */
        private Long deptId;

        /** 角色类型（默认合作人） */
        private String roleType;

        /** 新角色人业绩金额 */
        private BigDecimal amount;

        /** 新角色人角色占比（可空） */
        private BigDecimal shareRatio;
    }
}
