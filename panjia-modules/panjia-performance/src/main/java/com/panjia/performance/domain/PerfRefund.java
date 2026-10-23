package com.panjia.performance.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退单申请单（对应 pj_perf_refund 表）。
 * <p>
 * 与「手工冲正（MANUAL_OFFSET）」是两个概念：
 * <ul>
 *   <li>手工冲正：只产生负业绩事实，改数不改单据状态，可反复追加；</li>
 *   <li>退单：以<b>合同</b>为单位全额冲退，审批通过后同时生成
 *       负新签（PERF_EXPECT）与负实收（PERF_REAL）流水，该单据本身即
 *       「合同已退单」的状态源，后续统计直接查本表。</li>
 * </ul>
 * 审批流 flowCode=perf_refund（bizType=PERF_REFUND）：申请人 → 总监审批。
 */
@Data
@TableName("pj_perf_refund")
public class PerfRefund implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 退单 ID（雪花 ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 退单号（TD 前缀，唯一） */
    private String refundNo;

    /** 合同号（退单主体） */
    private String contractNo;

    /** 订单号（可空；同合同多订单时精确冲退） */
    private String orderNo;

    /** 退单落账期间 YYYY-MM（负流水归属月） */
    private String refundPeriod;

    /** 业务类型（一手房/房产金融/二手房等，创建时从业绩事实回填） */
    private String bizType;

    /** 员工 ID（首条新签事实回填，列表展示用） */
    private Long employeeId;

    /** 部门 ID（首条新签事实回填，列表展示/数据权限用） */
    private Long deptId;

    /** 冲减新签合计（负数；本次生成的新签负事实合计） */
    private BigDecimal expectAmount;

    /** 冲减实收合计（负数；本次生成的实收负明细合计；无实收为 0） */
    private BigDecimal realAmount;

    /** 冲减结佣合计（负数；已结佣部分生成的负结佣明细合计，未结佣部分待后续发起结佣自然抵扣） */
    private BigDecimal commissionAmount;

    /** 退单原因 */
    private String reason;

    /** 详情快照 JSON（明细行：factId/员工/角色/金额/归属月） */
    private String payloadJson;

    /** 状态：SUBMITTED/APPROVED/REJECTED/CANCELLED/EXECUTED（复用 {@link AdjustStatus}） */
    private AdjustStatus status;

    /** 审批流程实例 ID */
    private String processInstanceId;

    /** 申请人 ID */
    private Long applicantId;

    /** 审批人 ID */
    private Long approverId;

    /** 审批时间 */
    private LocalDateTime approveTime;

    /** 执行人 ID */
    private Long operatorId;

    /** 执行时间 */
    private LocalDateTime executeTime;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;

    /** 更新时间（DB 默认填充） */
    private LocalDateTime updateTime;

    /** 部门名称（展示用，sys_dept 批量回填） */
    @TableField(exist = false)
    private String deptName;
}
