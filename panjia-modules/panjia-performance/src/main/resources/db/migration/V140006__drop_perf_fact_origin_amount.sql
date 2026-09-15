-- 删除业绩事实表的 origin_amount 列（冗余字段，不再使用）
-- 统一使用 performance_amount 作为唯一金额口径
ALTER TABLE pj_perf_fact DROP COLUMN IF EXISTS origin_amount;
-- 删除业绩事实表的 conversion_rate 列（历史遗留字段，无数据来源，不参与计算）
-- 仅保留 shareRatio 作为展示用的业绩比例字段（来自贝壳导入）
ALTER TABLE pj_perf_fact DROP COLUMN IF EXISTS conversion_rate;
