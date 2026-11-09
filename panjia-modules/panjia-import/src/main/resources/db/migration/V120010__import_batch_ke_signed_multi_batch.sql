-- ============================================================
-- 导入批次唯一性：新签（KE_SIGNED）允许同期间多批次并存
-- ============================================================
-- 背景（2026-11-09 新签增量导入）：
--   新签导入由「同期间整批替换」改为「按事实 source_key 增量追加」，
--   同 (source_type, period, dept_id) 允许存在多个未废弃的 ARCHIVED 批次；
--   批次不再作为替换单位，而是审计单位。
--   已存在的业绩事实由 source_key + fact_type + ACTIVE 幂等锚点跳过，
--   已被调整过的事实不会被重导覆盖（金额修正仍走业绩调整流程）。
--   其他来源（ATTENDANCE 考勤、HISTORY_PAYROLL 历史工资）仍按「新批替换旧批」，
--   保持唯一索引约束。
-- 与 V120008（POINTS）、V120009（KE_RECEIVED）做法一致。
-- ============================================================

DROP INDEX IF EXISTS uk_import_batch_type_period_dept;

CREATE UNIQUE INDEX uk_import_batch_type_period_dept
    ON pj_import_batch(source_type, period, dept_id)
    WHERE superseded_by_batch_id IS NULL
      AND status = 3
      AND source_type <> 'POINTS'
      AND source_type <> 'KE_RECEIVED'
      AND source_type <> 'KE_SIGNED';

COMMENT ON INDEX uk_import_batch_type_period_dept IS
    '同(来源类型,期间,部门)只允许一个生效批次；POINTS（同月多文件合并）、KE_RECEIVED（按订单/月增量合并）、KE_SIGNED（按事实 source_key 增量追加）例外';
