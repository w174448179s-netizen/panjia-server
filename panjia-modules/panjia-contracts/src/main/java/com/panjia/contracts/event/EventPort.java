package com.panjia.contracts.event;

/**
 * 事件发布端口（横切能力 Port）。
 * <p>
 * 业务代码只依赖此接口，不耦合具体投递实现（Outbox / MQ / HTTP 由 Adapter 决定）。
 * <p>
 * 事务契约（P0，严格）：
 * <ul>
 *   <li>实现方法必须加 {@code @Transactional(propagation = Propagation.MANDATORY)}，
 *       强制要求外部业务事务上下文，无事务则抛 {@code IllegalTransactionStateException}</li>
 *   <li>只在「业务数据变更的写事务」内调用；只读逻辑禁止调用 emit</li>
 *   <li>调用 emit 的 Service 方法必须加 {@code @Transactional(rollbackFor = Exception.class)}</li>
 *   <li>{@code IllegalTransactionStateException} 为系统异常，业务层禁止捕获吞掉
 *       （吞掉会破坏 Outbox 原子性：事件未落 outbox，业务却提交了）</li>
 * </ul>
 */
public interface EventPort {

    /**
     * 发布领域事件到 Outbox（与业务事务原子提交）。
     *
     * @param event 领域事件，payload 只允许基础类型 / Long / POJO / Snapshot，禁止持有其他域 @Entity
     */
    void emit(DomainEvent event);
}
