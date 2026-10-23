package com.panjia.payroll.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.panjia.common.constant.PanjiaTransConstant;
import lombok.Data;
import org.dromara.common.translation.annotation.Translation;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 经纪人自购单提成（公司内部员工福利，对应 pj_payroll_self_purchase 表）。
 * <p>
 * 登记「合同号 + 经纪人」→ warm-flow（self_purchase_approval）总监审批 →
 * 审批通过（APPROVED）后在算薪时对该经纪人在<b>该合同号</b>上的结佣提点额外
 * 加 {@link #bonusRate}（默认 0.10 = +10 个百分点）。
 * <p>
 * 仅影响该员工在该合同号下的业绩分成：其他合同、该合同其他参与经纪人的提点不变；
 * 仅经纪人角色生效（店长/总监不适用）。
 */
@Data
@TableName("pj_payroll_self_purchase")
public class SelfPurchase implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：待提交 */
    public static final String STATUS_DRAFT = "DRAFT";

    /** 状态：审批中 */
    public static final String STATUS_SUBMITTED = "SUBMITTED";

    /** 状态：已通过（生效中） */
    public static final String STATUS_APPROVED = "APPROVED";

    /** 状态：已驳回 */
    public static final String STATUS_REJECTED = "REJECTED";

    /** 状态：已撤销（终态，不再生效） */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 默认加成：+10 个百分点 */
    public static final BigDecimal DEFAULT_BONUS_RATE = new BigDecimal("0.10");

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 合同号（命中该合同号下的结佣明细） */
    private String contractNo;

    /** 享受加成的经纪人员工 ID */
    private Long employeeId;

    /** 提点加成（百分点绝对值，0.10=额外 +10 个百分点） */
    private BigDecimal bonusRate;

    /** 备注/原因 */
    private String remark;

    /** 状态：DRAFT/SUBMITTED/APPROVED/REJECTED/CANCELLED */
    private String status;

    /** Warm-Flow 流程实例 ID（self_purchase_approval 流程） */
    private String processInstanceId;

    /** 提交人用户 ID */
    private Long applyBy;

    /** 提交时间 */
    private LocalDateTime applyTime;

    /** 审批人（总监）用户 ID */
    private Long approveBy;

    /** 审批时间 */
    private LocalDateTime approveTime;

    /** 驳回原因 */
    private String rejectReason;

    @Version
    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 员工姓名（非入库字段；序列化时按 {@link #employeeId} 从员工档案表翻译）。
     * 业务角色无 system:user:query 权限，前端不查员工全量表，由后端统一翻译。
     */
    @TableField(exist = false)
    @Translation(type = PanjiaTransConstant.EMPLOYEE_ID_TO_NAME, mapper = "employeeId")
    private String employeeName;
}
