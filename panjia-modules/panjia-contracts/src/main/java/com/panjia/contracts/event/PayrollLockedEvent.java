package com.panjia.contracts.event;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 工资锁定事件。
 * <p>
 * 工资批次锁定后发布，ledger 域消费此事件汇总部门收支。
 * payload 约束：字段只用基础类型、Long、不可变 POJO、Snapshot，
 * 禁止持有其他域的 @Entity（CI check-event-payload 校验）。
 */
@Data
public class PayrollLockedEvent implements DomainEvent {

    /** 工资批次 ID */
    private long batchId;

    /** 部门 ID → 人工成本（用于部门收支汇总） */
    private Map<Long, BigDecimal> deptCostMap;

    @Override
    public String eventType() {
        return "payroll.locked";
    }

    @Override
    public long businessId() {
        return batchId;
    }
}
