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
 * 业绩调整单（对应 pj_perf_adjust 表）。
 * <p>
 * 调整单用于对已存在的业绩事实进行人工干预，支持金额调整、业绩冲销、部门划转三种类型。
 * 调整状态由 {@link AdjustStatus} 管理，走审批流程。
 * <p>
 * 不继承 RuoYi BaseEntity：本表无 create_by/update_by 审计列，
 * create_time/update_time 由数据库默认值填充。
 */
@Data
@TableName("pj_perf_adjust")
public class PerformanceAdjust implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 调整单 ID（雪花 ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 调整单号（唯一） */
    private String adjustNo;

    /** 关联业绩事实 ID */
    private Long factId;

    /** 归属期间 YYYY-MM */
    private String period;

    /** 员工 ID */
    private Long employeeId;

    /** 原部门 ID */
    private Long deptId;

    /** 调整类型：AMOUNT-金额调整 / VOID-业绩冲销 / TRANSFER-部门划转 */
    private AdjustType adjustType;

    /** 调整范围：CONTRACT-合同级 / DETAIL-明细级 */
    private String adjustScope;

    /** 合同号（合同级调整时填，用于定位该合同下全部明细事实） */
    private String contractNo;

    /** 事实口径：PERF_REAL-结佣业绩 / PERF_EXPECT-新签业绩 */
    private String factType;

    /** 调整详情 JSON */
    private String payloadJson;

    /** 金额变动值 */
    private BigDecimal deltaAmount;

    /** 目标部门 ID（划转类） */
    private Long targetDeptId;

    /** 调整原因 */
    private String reason;

    /** 状态：SUBMITTED-已提交 / APPROVED-审批通过 / REJECTED-已拒绝 / CANCELLED-已取消 / EXECUTED-已执行 */
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

    // ==================== 展示字段（不入库，列表查询时回填） ====================

    /** 员工姓名（列表展示用，由 pj_people_employee 回填） */
    @TableField(exist = false)
    private String employeeName;

    /** 原部门名称（列表展示用，由 sys_dept 回填） */
    @TableField(exist = false)
    private String deptName;

    /** 目标部门名称（划转类展示用，由 sys_dept 回填） */
    @TableField(exist = false)
    private String targetDeptName;
}
