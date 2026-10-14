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
 * 实收调整单（对应 pj_perf_received_adjust 表）。
 * <p>
 * 对合同实收业绩（PERF_REAL，落表 pj_received_detail）做合同级金额调整：
 * 录入调整后实收合计，按当前实收金额占比等比分摊到各明细行，审批通过后执行。
 * <p>
 * 2026-10-12 与新签调整物理拆表（原 adjust_type=RECEIVED_AMOUNT 共用 pj_perf_adjust），
 * 独立审批流 flowCode=perf_received_adjust（bizType=PERF_RECEIVED_ADJUST）。
 * <p>
 * 不继承 RuoYi BaseEntity：本表无 create_by/update_by 审计列，
 * create_time/update_time 由数据库默认值填充。
 */
@Data
@TableName("pj_perf_received_adjust")
public class ReceivedAdjust implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 调整单 ID（雪花 ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 调整单号（ADJ 前缀，唯一） */
    private String adjustNo;

    /** 实收归属期间 YYYY-MM */
    private String period;

    /** 员工 ID（首条实收明细回填，列表展示用） */
    private Long employeeId;

    /** 部门 ID（首条实收明细回填，列表展示/数据权限用） */
    private Long deptId;

    /** 合同号 */
    private String contractNo;

    /** 订单号（可空；同合同号多订单时双键精确匹配实收明细） */
    private String orderNo;

    /** 业务类型（一手房/房产金融/二手房等，创建时从实收合同回填） */
    private String bizType;

    /** 分摊快照 JSON（AddMemberPayload：contractTotal/afterTotal/allocations，before+delta 绝对值语义） */
    private String payloadJson;

    /** 调整后实收合计（用户录入的最终金额） */
    private BigDecimal targetAmount;

    /** 调整前实收合计（创建时快照） */
    private BigDecimal originalAmount;

    /** 调整原因 */
    private String reason;

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
