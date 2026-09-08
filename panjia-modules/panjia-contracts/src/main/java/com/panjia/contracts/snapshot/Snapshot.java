package com.panjia.contracts.snapshot;

/**
 * 快照标记接口。
 * <p>
 * 所有快照类（如 EmployeeSnapshot）实现此接口；无业务方法，仅做类型标记，
 * 用于 CI / ArchUnit 校验事件 payload 禁止含 @Entity 实体类型。
 * <p>
 * 快照按其消费域命名：算薪快照 pj_payroll_employee_snapshot，结佣快照 pj_commission_employee_snapshot，
 * 不归 people 域（见架构 §3.4）。
 */
public interface Snapshot {
}
