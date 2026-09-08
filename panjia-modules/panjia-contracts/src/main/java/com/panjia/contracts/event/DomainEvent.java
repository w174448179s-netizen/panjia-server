package com.panjia.contracts.event;

/**
 * 领域事件契约接口。
 * <p>
 * 所有跨域事件必须实现此接口；事件 payload 只允许基础类型、Long、不可变 POJO、Snapshot，
 * 禁止持有其他域的 @Entity（CI check-event-payload 校验）。
 * <p>
 * tenantId V1 恒 null，预留多租户演进。
 */
public interface DomainEvent {

    /**
     * 事件类型标识，用于路由与序列化（如 "payroll.locked"）。
     *
     * @return 事件类型字符串
     */
    String eventType();

    /**
     * 关联业务 ID（如工资批次 ID），用于幂等与审计。
     *
     * @return 业务主键
     */
    long businessId();

    /**
     * 租户 ID，V1 恒 null，预留多租户。
     *
     * @return 租户 ID 或 null
     */
    default Long tenantId() {
        return null;
    }
}
