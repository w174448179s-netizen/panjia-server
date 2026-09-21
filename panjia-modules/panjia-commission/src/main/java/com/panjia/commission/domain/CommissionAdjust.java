package com.panjia.commission.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.dromara.common.translation.annotation.Translation;
import org.dromara.common.translation.constant.TransConstant;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 结佣调整单（对应 pj_commission_adjust 表）。
 * <p>
 * 重构后对齐新签调整（PerformanceAdjust）：直接操作业绩事实（PERF_REAL + PERF_EXPECT）。
 * EXECUTED 同事务动作：
 * <ul>
 *   <li>AMOUNT（金额调整）：supersede PERF_REAL 事实为 targetAmount，并以同一差额 supersede PERF_EXPECT；
 *       合同级按金额占比分摊，明细级单条替换；同步更新 CommissionItem.amount；</li>
 *   <li>VOID（业绩冲销）：冲销对应事实；</li>
 *   <li>TRANSFER（部门划转）：划转事实部门。</li>
 * </ul>
 * 列复用：{@code new_amount} 存 targetAmount（调整后金额），{@code diff_amount} 存 deltaAmount（调整差额，展示用）。
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

    /** 调整对象结佣明细 ID（合同级为 NULL） */
    private Long itemId;

    /** 调整明细所属业绩归属月（YYYY-MM） */
    private String period;

    /** 调整类型 AMOUNT/VOID/TRANSFER */
    private AdjustType adjustType;

    /** 调整后金额（AMOUNT 用，复用 new_amount 列） */
    private BigDecimal newAmount;

    /** 调整差额 = 调整后 − 调整前（展示用，复用 diff_amount 列；正增负减） */
    private BigDecimal diffAmount;

    /** 调整前金额（PERF_REAL 合计或单条明细金额） */
    private BigDecimal originalAmount;

    /** 调整对象合同号 */
    private String contractNo;

    /** 事实口径（PERF_REAL，结佣调整固定为实收业绩） */
    private String factType;

    /** 调整范围：CONTRACT-合同级 / DETAIL-明细级 */
    private String adjustScope;

    /** 明细级调整对应的业绩事实 ID（合同级为 NULL） */
    private Long factId;

    /** 部门划转目标部门 ID（TRANSFER 用） */
    private Long targetDeptId;

    /** 补发目标月（DIFF 用，已废弃保留列，始终 null） */
    private String targetPeriod;

    /** 变更前后值快照 JSON */
    private String payloadJson;

    /** 调整原因（必填，审计） */
    private String reason;

    /**
     * 折算后调整后金额（newAmount × 当前生效折算因子，展示用，不入库）。
     * <p>
     * 因子由调整单关联明细的 bizType 决定，经 ConversionFactorPort 取；newAmount 为空时保持 null。
     */
    @TableField(exist = false)
    private BigDecimal convertedNewAmount;

    /**
     * 折算后调整差额（diffAmount × 当前生效折算因子，展示用，不入库）。
     */
    @TableField(exist = false)
    private BigDecimal convertedDiffAmount;

    /** 状态 SUBMITTED/APPROVED/REJECTED/CANCELLED/EXECUTED */
    private AdjustStatus status;

    /** 审批流程实例 ID（预留 Warm-Flow） */
    private String processInstanceId;

    /** 发起人 ID */
    private Long applicantId;

    /**
     * 发起人昵称（非入库字段；序列化时按 {@link #applicantId} 翻译）。
     * 业务角色无 system:user:query 权限，前端不查用户表，由后端统一翻译。
     */
    @TableField(exist = false)
    @Translation(type = TransConstant.USER_ID_TO_NICKNAME, mapper = "applicantId")
    private String applicantName;

    /** 审批人 ID */
    private Long approverId;

    /** 审批人昵称（非入库字段；序列化时按 {@link #approverId} 翻译） */
    @TableField(exist = false)
    @Translation(type = TransConstant.USER_ID_TO_NICKNAME, mapper = "approverId")
    private String approverName;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;

    /** 更新时间（DB 默认填充） */
    private LocalDateTime updateTime;
}
