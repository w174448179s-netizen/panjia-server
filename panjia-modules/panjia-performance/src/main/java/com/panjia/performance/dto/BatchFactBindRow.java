package com.panjia.performance.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 导入批次内待绑定到实收审批单的事实行（批量自动建单中间结果）。
 * <p>
 * 一次查询取出批次内全部未绑定、非零 ACTIVE 实收事实，内存按 {@link #bizKey}
 * 分组后同时用于：①计算合同唯一归属门店；②收集 factId 批量回写 received_apply_id；
 * ③内存累加实收合计/条数。替代按合同逐单查询事实的 3N 次 SQL。
 */
@Data
public class BatchFactBindRow {

    /** 事实 ID */
    private Long factId;

    /** 业务键（一手房/房产金融/家装荐客取订单号，其余取合同号），与审批单聚合口径一致 */
    private String bizKey;

    /** 归属门店 ID */
    private Long deptId;

    /** 实收业绩金额 */
    private BigDecimal amount;
}
