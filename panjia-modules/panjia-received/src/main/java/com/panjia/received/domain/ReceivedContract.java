package com.panjia.received.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 实收合同主表（从 pj_perf_fact PERF_REAL 物理拆分）。
 * <p>
 * 一个合同 period 一条，聚合级。来源：贝壳实收导入（KE_RECEIVED）、历史工资导入（HISTORY_PAYROLL）。
 * 拆表后 pj_perf_fact 只存 PERF_EXPECT（新签/应收），本表存实收数据。
 */
@Data
@TableName("pj_received_contract")
public class ReceivedContract {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 订单号（业务锚点，全部业务类型统一订单号优先） */
    private String orderNo;

    /** 合同号（可能为空，UI 展示用） */
    private String contractNo;

    /** 业务类型 */
    private String bizType;

    /** 归属期间 YYYY-MM */
    private String period;

    /** 业务日期（成交日/到账日） */
    private LocalDateTime businessDate;

    /** 关联导入批次 ID */
    private Long batchId;

    /** 来源类型：KE_RECEIVED / HISTORY_PAYROLL */
    private String sourceType;

    /** 门店 ID */
    private Long deptId;

    /** 房源地址 */
    private String propertyAddress;

    /** 合同总金额（贝壳导入"合同金额"列） */
    private BigDecimal contractAmount;

    /** 本期实收合计（明细行 performance_amount 汇总） */
    private BigDecimal periodTotalReceived;

    /** 明细行数 */
    private Integer itemCount;

    /** 关联实收审批单 ID（审批通过后回填） */
    private Long receivedApplyId;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
