-- ============================================================
-- 实收明细：新增「被哪个批次替换」标记，支撑增量导入的精确回滚
-- ============================================================
-- 背景（2026-10-29 增量导入一期）：
--   实收导入改为按 订单/月 分组合并：明细有变化时，旧 ACTIVE 明细置 SUPERSEDED，
--   并记录被哪个批次替换（superseded_by_batch_id）。撤销该批次时据此恢复旧明细，
--   否则无法区分「被本批替换」与「历史已冲销」的行。
-- ============================================================

ALTER TABLE pj_received_detail
    ADD COLUMN IF NOT EXISTS superseded_by_batch_id BIGINT;

COMMENT ON COLUMN pj_received_detail.superseded_by_batch_id IS
    '被哪个导入批次替换而置为 SUPERSEDED；撤销该批次时按此恢复 ACTIVE（历史数据为空）';

CREATE INDEX IF NOT EXISTS idx_rd_superseded
    ON pj_received_detail(superseded_by_batch_id);
