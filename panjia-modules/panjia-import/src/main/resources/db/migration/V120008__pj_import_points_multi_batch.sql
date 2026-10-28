-- ============================================================
-- V120008
-- POINTS 允许同月多批次并存
--
-- 背景：同一归属月会分别导入二手积分日报、租赁积分日报、简版积分表（同 source_type=POINTS、
--       同 period、同部门各一份），下游员工域按 (工号, 填报日期) 合并，同日后导入者覆盖。
--       原唯一索引 status=3 时只允许一个 POINTS 批次生效，归档第二个会把第一个标为 superseded，
--       导致整月明细被回收，跨文件无法合并。
--
-- 处理：部分唯一索引排除 POINTS，其他来源（业绩、考勤）维持「新批替换旧批」。
-- ============================================================

DROP INDEX IF EXISTS uk_import_batch_type_period_dept;

CREATE UNIQUE INDEX uk_import_batch_type_period_dept
    ON pj_import_batch(source_type, period, dept_id)
    WHERE superseded_by_batch_id IS NULL
      AND status = 3
      AND source_type <> 'POINTS';

COMMENT ON INDEX uk_import_batch_type_period_dept IS
    '同(来源类型,期间,部门)只允许一个生效批次；POINTS 例外（同月二手/租赁/简版并存，按日期合并）';
