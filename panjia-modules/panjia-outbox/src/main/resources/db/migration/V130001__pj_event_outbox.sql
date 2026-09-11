-- ============================================================
-- Task-0-4: 事务性 Outbox 事件表
-- 段位：V130001（2026-09-11 由 V100004 重命名）
-- 归属域：panjia-outbox
-- 说明：存储待投递领域事件，与业务事务原子提交（MANDATORY 传播）
-- 依赖：V1 基线（sys_config / sys_dict 等底座已就绪）
-- 版本号说明：任务卡概念版本 V3，全局实际 V130001（outbox 域段位 50xxxx）
-- ============================================================

CREATE TABLE pj_event_outbox (
    id              BIGINT       PRIMARY KEY,                  -- 雪花 ID（应用层 ASSIGN_ID 生成，禁止 BIGSERIAL）
    event_id        VARCHAR(64)  NOT NULL UNIQUE,              -- 幂等键，应用层 UUID 生成
    event_type      VARCHAR(64)  NOT NULL,                     -- 如 payroll.locked
    aggregate_type  VARCHAR(64),                               -- 聚合类型（领域标识，如 payroll）
    aggregate_id    BIGINT,                                    -- 聚合 ID（如工资批次 ID）
    payload         JSONB        NOT NULL,                      -- Jackson 序列化 DomainEvent
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',    -- 见 OutboxStatusEnum：PENDING / PROCESSED / FAILED
    retry_count     INT          NOT NULL DEFAULT 0,
    error_message   TEXT,
    next_retry_at   TIMESTAMP,                                 -- 下次重试时间（指数退避：30*2^n，上限 600s）
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 应用层填充，不用触发器
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 部分索引：仅 PENDING 行进入投递扫描，减少索引体积
CREATE INDEX idx_outbox_pending ON pj_event_outbox(status, next_retry_at)
    WHERE status = 'PENDING';

COMMENT ON TABLE  pj_event_outbox IS '事务性 Outbox 事件表（业务表更新 + 事件插入原子提交，Dispatcher 异步投递）';
COMMENT ON COLUMN pj_event_outbox.id IS '主键，雪花 ID（应用层 ASSIGN_ID 生成）';
COMMENT ON COLUMN pj_event_outbox.event_id IS '幂等键，应用层 UUID 生成，唯一约束';
COMMENT ON COLUMN pj_event_outbox.event_type IS '事件类型，如 payroll.locked';
COMMENT ON COLUMN pj_event_outbox.aggregate_type IS '聚合类型（领域标识，如 payroll）';
COMMENT ON COLUMN pj_event_outbox.aggregate_id IS '聚合 ID（如工资批次 ID）';
COMMENT ON COLUMN pj_event_outbox.payload IS 'payload JSONB：Jackson 序列化 DomainEvent';
COMMENT ON COLUMN pj_event_outbox.status IS '投递状态：PENDING(待投递) / PROCESSED(成功) / FAILED(重试达上限)';
COMMENT ON COLUMN pj_event_outbox.retry_count IS '重试次数，达 10 标记 FAILED';
COMMENT ON COLUMN pj_event_outbox.error_message IS '最近一次失败错误信息';
COMMENT ON COLUMN pj_event_outbox.next_retry_at IS '下次重试时间（指数退避：now + min(30*2^retry_count, 600) 秒）';
COMMENT ON COLUMN pj_event_outbox.created_at IS '创建时间（应用层填充，不用触发器）';
COMMENT ON COLUMN pj_event_outbox.updated_at IS '更新时间（应用层填充，不用触发器）';
