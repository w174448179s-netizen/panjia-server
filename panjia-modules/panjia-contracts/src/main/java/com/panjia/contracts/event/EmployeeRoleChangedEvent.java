package com.panjia.contracts.event;

import lombok.Data;

/**
 * 员工角色变更事件。
 * <p>
 * 人事变更（AGENT → STORE_MANAGER 等）时由 people 域在事务内发布，
 * 监听器消费后通过映射表解绑旧系统角色、绑定新系统角色。
 * <p>
 * payload 约束：字段只用基础类型，禁止持有 @Entity。
 */
@Data
public class EmployeeRoleChangedEvent implements DomainEvent {

    /** 员工 ID */
    private long employeeId;

    /** 关联 sys_user.id（无登录账号时为 null，监听器跳过同步） */
    private Long userId;

    /** 旧业务角色（枚举名，如 AGENT） */
    private String oldRole;

    /** 新业务角色（枚举名，如 STORE_MANAGER） */
    private String newRole;

    /** 变更原因 */
    private String reason;

    /** 操作人 */
    private String operator;

    @Override
    public String eventType() {
        return "employee.role.changed";
    }

    @Override
    public long businessId() {
        return employeeId;
    }
}
