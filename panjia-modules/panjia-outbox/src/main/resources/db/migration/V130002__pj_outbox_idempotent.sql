-- ============================================================
-- Task-0-4: Outbox 幂等记录表
-- 段位：V130002（2026-09-11 由 V100005 重命名）
-- 归属域：panjia-outbox
-- 说明：消费前 INSERT，主键冲突 = 已消费 = 幂等跳过（V1 用 DB，减少 Redis 依赖）
-- 依赖：V130001（pj_event_outbox 已建表）
-- 版本号说明：任务卡概念版本 V5，全局实际 V130002（接续 V130001）
-- ============================================================

CREATE TABLE pj_outbox_idempotent (
    event_id      VARCHAR(64) PRIMARY KEY,                      -- 复用 pj_event_outbox.event_id（唯一幂等键）
    consumed_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  pj_outbox_idempotent IS 'Outbox 幂等记录表（消费前 INSERT，主键冲突 = 已消费 = 跳过）';
COMMENT ON COLUMN pj_outbox_idempotent.event_id IS '事件 ID（主键，复用 outbox.event_id）';
COMMENT ON COLUMN pj_outbox_idempotent.consumed_at IS '消费时间';
