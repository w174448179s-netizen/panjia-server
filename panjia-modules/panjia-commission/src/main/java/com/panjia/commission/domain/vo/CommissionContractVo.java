package com.panjia.commission.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.dromara.common.translation.annotation.Translation;
import org.dromara.common.translation.constant.TransConstant;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 结佣申请「合同」维度聚合行。
 * <p>
 * 与业绩明细页合同维度对齐：以合同为标准展示，相比业绩明细多出「状态」「发起人」两列，
 * 去掉「未结算」列（结佣申请范围内的明细均已锁定结算）。
 */
@Data
@NoArgsConstructor
public class CommissionContractVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 关联申请单 ID */
    private Long applicationId;

    /** 申请单号 */
    private String applyNo;

    /** 合同号 */
    private String contractNo;

    /** 订单号 */
    private String orderNo;

    /** 业务类型 */
    private String bizType;

    /** 房源地址 */
    private String propertyAddress;

    /** 签约/认购时间 */
    private LocalDateTime businessDate;

    /** 合同结佣金额合计（实收口径；§3.5 对齐后=应收合计） */
    private BigDecimal amount;

    /** 结佣业绩折算后金额（amount × conversionFactor） */
    private BigDecimal convertedAmount;

    /** 应收业绩合计（差异判定展示） */
    private BigDecimal expectedAmount;

    /** 新签业绩折算后金额（expectedAmount × conversionFactor） */
    private BigDecimal expectedConvertedAmount;

    /** 调整前应收业绩合计（申请单提交时快照；仅 expectedAdjusted=true 时有值，供展示「原值 → 调整后值」） */
    private BigDecimal originalExpectedAmount;

    /** 调整前应收的折算后金额（originalExpectedAmount × conversionFactor） */
    private BigDecimal originalExpectedConvertedAmount;

    /** 应收已被调整（当前 ACTIVE 应收与提交快照不一致时置 true） */
    private Boolean expectedAdjusted;

    /** 合同存在已生效的「增加角色人」（有 ACTIVE 的 MANUAL-ADJ/MANUAL-CADJ 新签事实行） */
    private Boolean hasAddMember;

    /** 是否已发生实收对齐应收 */
    private Boolean aligned;

    /** 当前审批节点 DIRECTOR/FINANCE */
    private String currentNode;

    /** 涉及签约人数（去重） */
    private long employeeCount;

    /** 明细条数 */
    private long detailCount;

    /** 业绩归属月 */
    private String period;

    /**
     * 合并展示的期间串（未选期间时同一合同多期申请单合并为一行，
     * 形如「2026-08、2026-09、2026-10」；选定期查询时为空）。
     */
    private String periods;

    /** 该期间是否已封账（封账后不可作废/调整，仅展示） */
    private Boolean periodClosed;

    /** 门店 ID */
    private Long deptId;

    /** 申请单状态（DRAFT/SUBMITTED/LOCKED/REJECTED/CANCELLED） */
    private String status;

    /** 实收审批状态（APPROVED/SUBMITTED/DRAFT/null） */
    private String receivedStatus;

    /** 发起人 ID */
    private Long applicantId;

    /**
     * 发起人昵称（非入库字段；序列化时按 {@link #applicantId} 翻译）。
     * 业务角色无 system:user:query 权限，前端不查用户表，由后端统一翻译。
     */
    @Translation(type = TransConstant.USER_ID_TO_NICKNAME, mapper = "applicantId")
    private String applicantName;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 实收金额合计（PERF_REAL 事实当前 ACTIVE 金额求和，即客户实际到账） */
    private BigDecimal receivedAmount;

    /**
     * 调整前实收金额合计（当前 ACTIVE rd 的 sourceKey 链最早金额求和，实收加人新人行按 0；
     * 仅 {@link #receivedAmountAdjusted}=true 时有值，供展示「原值 → 调整后值」）。
     */
    private BigDecimal originalReceivedAmount;

    /** 实收金额已被实收调整（链原值 ≠ 当前合计；业绩调整 AMOUNT 总额不变时不置标记） */
    private Boolean receivedAmountAdjusted;
}
