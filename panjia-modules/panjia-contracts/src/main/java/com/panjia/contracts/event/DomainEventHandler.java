package com.panjia.contracts.event;

/**
 * 领域事件消费端口（横切能力 Port）。
 * <p>
 * 各业务域实现此接口消费 Outbox 事件，Dispatcher（panjia-outbox）按
 * {@link #eventType()} 路由调用。业务域只依赖 contracts 本端口，
 * <b>不反向依赖 panjia-outbox</b>（依赖方向单向向内：outbox → contracts ← 业务域），
 * 避免兄弟域之间横向耦合。
 * <p>
 * 实现类需标注 {@code @Component}，Spring 自动收集为 {@code List<DomainEventHandler>}
 * 注入 Dispatcher。
 * <p>
 * 幂等保证：Dispatcher 在调用前已做 pj_outbox_idempotent 幂等检查，
 * 实现方只需关注业务逻辑。
 */
public interface DomainEventHandler {

    /**
     * 处理的事件类型（如 "employee.role.changed"），需与 {@link DomainEvent#eventType()} 一致。
     *
     * @return 事件类型字符串
     */
    String eventType();

    /**
     * 处理领域事件。
     * <p>
     * payload 为事件 JSON 串，实现方自行反序列化为具体事件类型；
     * 反序列化失败应记录日志并返回（不可抛出阻断 Dispatcher 对后续事件的投递）。
     *
     * @param eventId     事件唯一 ID（日志追踪用，与 pj_event_outbox.event_id 一致）
     * @param payloadJson 事件 payload JSON 字符串
     */
    void handle(String eventId, String payloadJson);
}
