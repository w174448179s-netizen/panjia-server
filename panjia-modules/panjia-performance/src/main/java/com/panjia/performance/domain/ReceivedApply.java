package com.panjia.performance.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 实收业绩审批单（对应 pj_perf_received_apply 表，聚合根）。
 * <p>
 * 粒度 = 合同 + 结算月：贝壳业绩导入产生实收业绩事实后，若合同存在实收，
 * 系统按 (period, contractNo) 自动建单并提交审批（§2.1）；店长/财务/总监
 * 也可手工提交，按发起人角色路由审批节点（§2.2）。
 */
@Data
@TableName("pj_perf_received_apply")
public class ReceivedApply implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 审批单号 RCV+yyyyMMddHHmmssSSS */
    private String applyNo;

    /** 结算月 YYYY-MM */
    private String period;

    /** 合同号 */
    private String contractNo;

    /** 订单号（快照） */
    private String orderNo;

    /** 物业地址（快照） */
    private String propertyAddress;

    /** 签约/业务时间（快照，取合同内最大） */
    private LocalDateTime businessDate;

    /** 归属门店 ID（跨门店合作单为空） */
    private Long deptId;

    /** 首次生成该单的导入批次 ID（手工建单可空） */
    private Long batchId;

    /** 实收业绩合计 */
    private BigDecimal receivedAmount;

    /** 应收业绩合计（本次导入/提交时记录，§1 合同历史调整后取最新应收） */
    private BigDecimal expectedAmount;

    /** 明细条数（实收事实条数） */
    private Integer itemCount;

    /** 状态 DRAFT/SUBMITTED/APPROVED/REJECTED/CANCELLED */
    private ReceivedApplyStatus status;

    /** 当前审批节点 FINANCE/DIRECTOR；终审/驳回/作废时需写回 null，故 ALWAYS 参与 update */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String currentNode;

    /** 工作流实例 ID */
    private String processInstanceId;

    /** 发起人 ID（系统自动发起为空） */
    private Long applicantId;

    /** 终审人 ID */
    private Long approverId;

    /** 终审时间 */
    private LocalDateTime approveTime;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;

    /** 更新时间（DB 默认填充） */
    private LocalDateTime updateTime;
}
