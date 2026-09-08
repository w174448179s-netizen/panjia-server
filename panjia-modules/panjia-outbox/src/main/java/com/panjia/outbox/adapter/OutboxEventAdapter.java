package com.panjia.outbox.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panjia.contracts.event.DomainEvent;
import com.panjia.contracts.event.EventPort;
import com.panjia.contracts.event.OutboxStatusEnum;
import com.panjia.outbox.entity.OutboxEvent;
import com.panjia.outbox.mapper.OutboxEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Outbox 事件 Adapter（实现 {@link EventPort}）。
 * <p>
 * 事务契约（P0，严格）：
 * <ul>
 *   <li>{@link #emit} 标注 {@code @Transactional(propagation = Propagation.MANDATORY)}，
 *       强制要求外部业务事务上下文；无事务则 Spring 抛 {@code IllegalTransactionStateException}</li>
 *   <li>{@code IllegalTransactionStateException} 为系统异常，本类不捕获吞掉，
 *       由 RuoYi GlobalExceptionHandler 统一返回 500</li>
 *   <li>调用方 Service 方法必须加 {@code @Transactional(rollbackFor = Exception.class)}，
 *       保证业务表更新 + outbox INSERT 原子提交</li>
 * </ul>
 * <p>
 * 适配说明：任务卡伪代码用 {@code JSON.toJSONString(event)}（Gson 风格），
 * 本实现遵循 00 卡规约「统一 Jackson」——注入 Spring ObjectMapper Bean 序列化 payload。
 */
@Slf4j
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class OutboxEventAdapter implements EventPort {

    @Autowired
    private OutboxEventMapper outboxEventMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void emit(DomainEvent event) {
        OutboxEvent outbox = new OutboxEvent();
        outbox.setEventId(UUID.randomUUID().toString());
        outbox.setEventType(event.eventType());
        // 聚合类型取事件类型的前缀（如 payroll.locked → payroll），下游可按需覆盖
        outbox.setAggregateType(resolveAggregateType(event.eventType()));
        outbox.setAggregateId(event.businessId());
        outbox.setPayload(serializePayload(event));
        outbox.setStatus(OutboxStatusEnum.PENDING);
        outbox.setRetryCount(0);
        LocalDateTime now = LocalDateTime.now();
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        // next_retry_at 留空，Dispatcher 立即拉取投递

        outboxEventMapper.insert(outbox);
    }

    /**
     * 用 Jackson 序列化 DomainEvent 为 payload JSON 字符串。
     * <p>
     * 序列化失败为系统异常，抛 ServiceException（不吞掉，不破坏事务原子性）。
     *
     * @param event 领域事件
     * @return payload JSON 字符串
     */
    private String serializePayload(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // 序列化失败视为系统异常，抛业务异常让事务回滚
            throw new ServiceException("Outbox payload 序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按事件类型解析聚合类型（事件类型前缀）。
     * <p>
     * 例：payroll.locked → payroll；无点号则原样返回。
     *
     * @param eventType 事件类型
     * @return 聚合类型
     */
    private String resolveAggregateType(String eventType) {
        int dot = eventType.indexOf('.');
        return dot > 0 ? eventType.substring(0, dot) : eventType;
    }
}
