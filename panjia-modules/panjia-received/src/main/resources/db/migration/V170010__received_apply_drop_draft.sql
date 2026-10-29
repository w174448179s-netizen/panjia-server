-- ============================================================
-- 实收审批：彻底移除草稿态（2026-10-29 去草稿）
-- ============================================================
-- 背景：
--   DRAFT 单仅为「先导实收、后到新签」等待新签匹配而存在，无 UI 入口、长期悬挂。
--   改造后「待新签匹配」状态由「实收明细 received_apply_id 为空」承载：
--   实收先到时明细照常落库；新签导入后由 resolvePendingAfterNewSign 补建审批单，
--   达标自动通过、否则转人工审批（两条链路均适用）。
-- 清理：解绑存量 DRAFT 单占用的明细/合同引用，再直接物理删除 DRAFT 单。
-- ============================================================

UPDATE pj_received_detail d
   SET received_apply_id = NULL
  FROM pj_perf_received_apply a
 WHERE d.received_apply_id = a.id
   AND a.status = 'DRAFT';

UPDATE pj_received_contract c
   SET received_apply_id = NULL
  FROM pj_perf_received_apply a
 WHERE c.received_apply_id = a.id
   AND a.status = 'DRAFT';

-- 历史 PERF_REAL 事实可能仍指向草稿单（拆表后已不再新增），一并解绑避免悬挂引用
UPDATE pj_perf_fact f
   SET received_apply_id = NULL
  FROM pj_perf_received_apply a
 WHERE f.received_apply_id = a.id
   AND a.status = 'DRAFT';

DELETE FROM pj_perf_received_apply WHERE status = 'DRAFT';
