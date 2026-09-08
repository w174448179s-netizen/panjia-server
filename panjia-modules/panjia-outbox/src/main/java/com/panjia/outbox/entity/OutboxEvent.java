package com.panjia.outbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.panjia.contracts.event.OutboxStatusEnum;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Outbox 事件实体（对应 pj_event_outbox 表）。
 * <p>
 * 与业务事务原子提交：业务表更新 + outbox INSERT 在同一事务。
 * <p>
 * 不继承 PanjiaBaseEntity——outbox 表无 created_by/updated_by/create_dept 审计字段，
 * 时间字段 created_at/updated_at 由应用层在 emit / 状态更新时手动设置（不用触发器）。
 */
@Data
@TableName("pj_event_outbox")
public class OutboxEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键，雪花 ID（应用层 ASSIGN_ID 生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 幂等键，应用层 UUID 生成（唯一约束） */
    private String eventId;

    /** 事件类型，如 payroll.locked */
    private String eventType;

    /** 聚合类型（领域标识，如 payroll.batch） */
    private String aggregateType;

    /** 聚合 ID（如工资批次 ID） */
    private Long aggregateId;

    /** payload JSON 串（Jackson 序列化 DomainEvent），DB 字段 JSONB */
    private String payload;

    /** 投递状态：PENDING / PROCESSED / FAILED */
    private OutboxStatusEnum status;

    /** 重试次数 */
    private Integer retryCount;

    /** 最近一次失败错误信息 */
    private String errorMessage;

    /** 下次重试时间（指数退避：now + min(30*2^retryCount, 600) 秒） */
    private LocalDateTime nextRetryAt;

    /** 创建时间（应用层填充，不用触发器） */
    private LocalDateTime createdAt;

    /** 更新时间（应用层填充，不用触发器） */
    private LocalDateTime updatedAt;
}
