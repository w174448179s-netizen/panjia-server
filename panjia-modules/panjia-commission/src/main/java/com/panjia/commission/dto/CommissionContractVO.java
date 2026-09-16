package com.panjia.commission.dto;

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
public class CommissionContractVO implements Serializable {

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

    /** 应收业绩合计（差异判定展示） */
    private BigDecimal expectedAmount;

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
}
