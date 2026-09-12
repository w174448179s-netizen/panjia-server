package com.panjia.commission.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 结佣调整单（对应 pj_commission_adjust 表）。
 * <p>
 * 已审批结佣数据变更的唯一入口（V4.2 §9.4）。EXECUTED 同事务动作（§3.3）：
 * <ul>
 *   <li>DISCOUNT：旧明细 REVERSED + 新明细（amount = 折后值，performance_fact_id 沿用原值）；</li>
 *   <li>DIFF：新增差额明细（performance_fact_id = NULL，period = target_period）；</li>
 *   <li>VOID：旧明细 REVERSED。</li>
 * </ul>
 */
@Data
@TableName("pj_commission_adjust")
public class CommissionAdjust implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 调整单号 CADJ+yyyyMMdd+序列 */
    private String adjustNo;

    /** 调整对象申请单 ID */
    private Long applicationId;

    /** 调整对象结佣明细 ID（整单级调整时为 NULL，预留） */
    private Long itemId;

    /** 调整明细所属业绩归属月（YYYY-MM） */
    private String period;

    /** 调整类型 DISCOUNT/DIFF/VOID */
    private AdjustType adjustType;

    /** 折后最终金额（DISCOUNT 用，直接存折后值，非系数） */
    private BigDecimal newAmount;

    /** 差额金额（DIFF 用，正补负扣） */
    private BigDecimal diffAmount;

    /** 补发目标月（DIFF 用 YYYY-MM；封账校验按此月判定） */
    private String targetPeriod;

    /** 变更前后值快照 JSON */
    private String payloadJson;

    /** 调整原因（必填，审计；折扣种类如 85 折写此处） */
    private String reason;

    /** 状态 SUBMITTED/APPROVED/REJECTED/CANCELLED/EXECUTED */
    private AdjustStatus status;

    /** 审批流程实例 ID（预留 Warm-Flow） */
    private String processInstanceId;

    /** 发起人 ID */
    private Long applicantId;

    /** 审批人 ID */
    private Long approverId;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;

    /** 更新时间（DB 默认填充） */
    private LocalDateTime updateTime;
}
