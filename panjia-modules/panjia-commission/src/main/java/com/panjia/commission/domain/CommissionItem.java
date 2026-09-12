package com.panjia.commission.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 结佣明细（对应 pj_commission_item 表）。
 * <p>
 * 一行 = 一条 PERF_REAL 业绩事实的结佣确认。关键不变量（结佣域详细设计 §3.2）：
 * <ul>
 *   <li>已审批（APPROVED）明细金额不可变 —— 变更只能走调整单（新行）；</li>
 *   <li>REVERSED 行永久保留；</li>
 *   <li>部分唯一索引 uk_citem_fact_active 排除 REVERSED 行（DISCOUNT 调整新行沿用同一 fact_id）；</li>
 *   <li>DIFF 差额行不挂 performance_fact_id（差额凭证，不指向具体事实）。</li>
 * </ul>
 */
@Data
@TableName("pj_commission_item")
public class CommissionItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属申请单 ID */
    private Long applicationId;

    /** 关联业绩事实 ID（只存 ID 不建 FK；DIFF 差额行为 NULL） */
    private Long performanceFactId;

    /** 业绩归属月（YYYY-MM）；DIFF 差额行为补发目标月 */
    private String period;

    /** 工资归属月（审批通过月 YYYY-MM） */
    private String approvedMonth;

    /** 员工 ID（冻结快照） */
    private Long employeeId;

    /** 归属门店 ID（冻结快照） */
    private Long deptId;

    /** 业务类型（冻结快照） */
    private String bizType;

    /** 角色类型（冻结快照） */
    private String roleType;

    /** 费用项（冻结快照） */
    private String feeItem;

    /** 结佣业绩金额（业绩域原样透传，非佣金金额） */
    private BigDecimal amount;

    /** 状态 PENDING/APPROVED/REVERSED */
    private ItemStatus status;

    /** 源业绩事实已被冲销（已审批明细置 true + 告警，金额不动） */
    private Boolean originReversed;

    /** 来源结佣调整单 ID */
    private Long adjustId;

    /** 冲销原因 */
    private ReversedReason reversedReason;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;

    /** 更新时间（DB 默认填充） */
    private LocalDateTime updateTime;
}
