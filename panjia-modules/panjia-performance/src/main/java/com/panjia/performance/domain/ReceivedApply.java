package com.panjia.performance.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import org.dromara.common.translation.annotation.Translation;
import org.dromara.common.translation.constant.TransConstant;

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

    /** 应收业绩合计（提交时点快照，仅留痕；列表/详情展示实时取 ACTIVE PERF_EXPECT 含已生效调整） */
    private BigDecimal expectedAmount;

    /** 明细条数（实收事实条数） */
    private Integer itemCount;

    /**
     * 业务类型（一手房/二手买卖/租赁/租赁轻托管/写字楼租赁/轻托管推房/房产金融/家装荐客…）。
     * <p>建单时从实收事实快照落库（见 V140009），列表展示与「类型」筛选均直接取本列，
     * 不再依赖按 (period, contractNo) 实时回查事实。</p>
     */
    private String bizType;

    /**
     * 涉及人数（非入库字段；列表查询时回填该合同本期间实收事实的去重员工数）。
     */
    @TableField(exist = false)
    private Integer employeeCount;

    /**
     * 应收已被调整（非入库字段；展示标记）。
     * <p>当前 ACTIVE 应收合计 ≠ 提交时快照时置 true，前端据此显示「已调整」标记，
     * 让业务人员知道应收与实收不一致是业绩调整所致。</p>
     */
    @TableField(exist = false)
    private Boolean expectedAdjusted;

    /**
     * 调整前应收（非入库字段）＝提交时快照应收。
     * <p>列表/详情在把 {@link #expectedAmount} 覆盖为实时值前留存，供前端展示「原值 → 调整后值」；
     * 未调整（或老数据无快照）时为 null 或与 {@link #expectedAmount} 相等。</p>
     */
    @TableField(exist = false)
    private BigDecimal originalExpectedAmount;

    /** 应收业绩折算后（非入库字段；列表查询时按 bizType 折算） */
    @TableField(exist = false)
    private BigDecimal expectedConvertedAmount;

    /** 调整前应收的折算后金额（非入库字段；originalExpectedAmount × 与 expectedConvertedAmount 同一折算因子） */
    @TableField(exist = false)
    private BigDecimal originalExpectedConvertedAmount;

    /** 实收业绩折算后（非入库字段；列表查询时按 bizType 折算） */
    @TableField(exist = false)
    private BigDecimal receivedConvertedAmount;

    /** 状态 DRAFT/SUBMITTED/APPROVED/REJECTED/CANCELLED */
    private ReceivedApplyStatus status;

    /** 当前审批节点 FINANCE/DIRECTOR；终审/驳回/作废时需写回 null，故 ALWAYS 参与 update */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String currentNode;

    /** 工作流实例 ID */
    private String processInstanceId;

    /** 发起人 ID（系统自动发起为空） */
    private Long applicantId;

    /**
     * 发起人昵称（非入库字段；序列化时按 {@link #applicantId} 翻译）。
     * <p>
     * 业务角色（店长/财务/人事/经纪人）没有 system:user:query 权限，前端无法自行查用户表翻译，
     * 故由后端统一翻译。为空表示系统自动发起（如导入归档自动建单），前端展示「系统自动」。
     */
    @TableField(exist = false)
    @Translation(type = TransConstant.USER_ID_TO_NICKNAME, mapper = "applicantId")
    private String applicantName;

    /** 终审人 ID */
    private Long approverId;

    /** 终审人昵称（非入库字段；序列化时按 {@link #approverId} 翻译） */
    @TableField(exist = false)
    @Translation(type = TransConstant.USER_ID_TO_NICKNAME, mapper = "approverId")
    private String approverName;

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
