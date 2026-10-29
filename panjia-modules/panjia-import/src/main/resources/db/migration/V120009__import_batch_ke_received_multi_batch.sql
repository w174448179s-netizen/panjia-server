-- ============================================================
-- 导入批次唯一性：实收（KE_RECEIVED）允许同期间多批次并存
-- ============================================================
-- 背景（2026-10-29 增量导入一期）：
--   实收导入由「同期间整批替换」改为「按 订单/月 粒度增量合并」，
--   每次导入都留痕一份批次，同 (source_type, period, dept_id) 允许存在多个
--   未废弃的 ARCHIVED 批次；批次不再是替换单位，而是审计单位。
--   其他来源（KE_SIGNED 业绩、ATTENDANCE 考勤、HISTORY_PAYROLL 历史工资）仍按
--   「新批替换旧批」，保持唯一索引约束。
-- 与 V120008 排除 POINTS 的做法一致。
-- ============================================================

DROP INDEX IF EXISTS uk_import_batch_type_period_dept;

CREATE UNIQUE INDEX uk_import_batch_type_period_dept
    ON pj_import_batch(source_type, period, dept_id)
    WHERE superseded_by_batch_id IS NULL
      AND status = 3
      AND source_type <> 'POINTS'
      AND source_type <> 'KE_RECEIVED';

COMMENT ON INDEX uk_import_batch_type_period_dept IS
    '同(来源类型,期间,部门)只允许一个生效批次；POINTS（同月多文件合并）、KE_RECEIVED（按订单/月增量合并）例外';
