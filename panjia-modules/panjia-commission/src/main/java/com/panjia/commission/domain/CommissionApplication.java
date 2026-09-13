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
 * 结佣申请单（对应 pj_commission_application 表，聚合根）。
 * <p>
 * 粒度 = 合同 + 业绩归属月（结算月）：一个合同一个月一张申请单，独立提交/审批。
 * 合同可能跨门店合作（同合同多人分属不同门店），故 dept_id 仅作冗余快照、可为空。
 * total_amount 为<b>结佣业绩金额合计</b>（非佣金金额）。
 * <p>
 * 不继承 RuoYi BaseEntity：本表无 create_by/update_by 审计列，
 * create_time/update_time 由数据库默认值填充。
 */
@Data
@TableName("pj_commission_application")
public class CommissionApplication implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 申请单号 CAPP+yyyyMMdd+序列 */
    private String applyNo;

    /** 业绩归属月（结算月 YYYY-MM） */
    private String period;

    /** 合同号（业务聚合键，与业绩域 raw_signed.contract_no 一致） */
    private String contractNo;

    /** 订单号（一手房展示用，快照） */
    private String orderNo;

    /** 房源地址（快照） */
    private String propertyAddress;

    /** 签约/认购时间（快照） */
    private LocalDateTime businessDate;

    /** 门店 ID（冗余快照；跨门店合作单可能为空） */
    private Long deptId;

    /** 明细条数 */
    private Integer itemCount;

    /** 结佣业绩金额合计（原样透传，非佣金金额） */
    private BigDecimal totalAmount;

    /** 状态 DRAFT/SUBMITTED/APPROVED/LOCKED/REJECTED/CANCELLED */
    private ApplicationStatus status;

    /** 审批通过月 = 工资归属月（YYYY-MM，V4.2 硬要求 1） */
    private String approvedMonth;

    /** 审批流程实例 ID（预留 Warm-Flow） */
    private String processInstanceId;

    /** 发起人 ID */
    private Long applicantId;

    /** 审批人 ID */
    private Long approverId;

    /** 锁定时间 */
    private LocalDateTime lockTime;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;

    /** 更新时间（DB 默认填充） */
    private LocalDateTime updateTime;
}
