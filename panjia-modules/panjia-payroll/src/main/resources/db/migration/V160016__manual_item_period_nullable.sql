-- ===================================================================
-- V160016: 放开手工录入项 period 列非空约束
-- 背景：V160012 已将手工项归属月改为生效区间（start_month/end_month），
--       代码不再映射 period（列保留存历史），但初始 DDL 的 NOT NULL 约束
--       未同步放开，导致新增奖金/其他收支时 period 为 null 触发非空约束报错。
-- 处理：仅放开非空约束，列与历史数据保留不动。
-- ===================================================================

ALTER TABLE pj_payroll_manual_item
    ALTER COLUMN period DROP NOT NULL;

COMMENT ON COLUMN pj_payroll_manual_item.period IS '历史归属月（V160012 起废弃，代码不再写入；以 start_month/end_month 为准）';
