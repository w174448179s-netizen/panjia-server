package com.panjia.received.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 实收明细表（角色人级，从 pj_perf_fact PERF_REAL 物理拆分）。
 * <p>
 * 对应理房通到账明细一行一人，一个合同 period 下 N 行。
 * source_key 是幂等锚点：order_no + employee_external_code + period + effective_date。
 */
@Data
@TableName("pj_received_detail")
public class ReceivedDetail {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 外键 → pj_received_contract.id */
    private Long contractId;

    /** 角色人员工 ID（空经纪人场景可为 NULL，仅展示不参与计算） */
    private Long employeeId;

    /** 明细级归属门店（空取合同级 rc.dept_id；结佣单行划转时写目标部门） */
    private Long deptId;

    /** 员工外部编码（贝壳员工号） */
    private String employeeExternalCode;

    /** 角色类型 */
    private String roleType;

    /** 角色姓名 */
    private String roleName;

    /** 分佣比例 */
    private BigDecimal shareRatio;

    /** 实收金额（角色人到账金额，可为负） */
    private BigDecimal performanceAmount;

    /** 费用项 */
    private String feeItem;

    /** 归属期间 YYYY-MM */
    private String period;

    /** 生效日期 */
    private LocalDate effectiveDate;

    /** 失效日期 */
    private LocalDate expireDate;

    /** 幂等锚点：order_no + employee_external_code + period + effective_date */
    private String sourceKey;

    /** 来源批次 ID */
    private Long sourceBatchId;

    /** 归一化记录 ID */
    private Long normalizedRecordId;

    /** 关联实收审批单 ID（审批通过后回填） */
    private Long receivedApplyId;

    /** 明细状态：ACTIVE=有效 / REVERSED=已红冲（对应调整） */
    private String detailStatus;

    /** 关联调整单 ID（结佣调整/新签调整；仅 supersede 新行有值） */
    private Long adjustId;

    /** 本行被冲销原因（ReversedReason code；仅 REVERSED 行有值） */
    private String reversalType;

    /** 退款/红冲指向的原始实收明细行 ID */
    private Long refundOfDetailId;

    /** 操作人 ID */
    private Long operatorId;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
