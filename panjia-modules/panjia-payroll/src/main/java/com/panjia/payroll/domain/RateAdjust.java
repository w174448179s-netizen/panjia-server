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
 * 提成点调整单（员工业绩扣点，总监审批后按生效区间在算薪时叠加）。
 * <p>
 * 人工登记项（电话考核未完成/个人调整等）走 warm-flow（rate_adjust_approval）；
 * 未买社保扣点由档案参保事实自动判断，不经本表（见引擎 policy.noSocialDeduct）。
 */
@Data
@TableName("pj_payroll_rate_adjust")
public class RateAdjust implements Serializable {

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

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 员工 ID */
    private Long employeeId;

    /** 调整类型（字典 rate_adjust_type：NO_SOCIAL/PHONE_CHECK/PERSONAL） */
    private String adjustType;

    /** 调整点数（负值=扣点，如 -0.02 扣 2 个点） */
    private BigDecimal adjustRate;

    /** 生效起始月 YYYY-MM（含） */
    private String startMonth;

    /** 生效结束月 YYYY-MM（含，null=长期有效至撤销） */
    private String endMonth;

    /** 调整原因（必填） */
    private String reason;

    /** 状态：DRAFT/SUBMITTED/APPROVED/REJECTED/CANCELLED */
    private String status;

    /** Warm-Flow 流程实例 ID（rate_adjust_approval 流程） */
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
