-- ============================================================
-- 修复业绩折算规则 biz_type 与业务事实表口径不一致
-- ------------------------------------------------------------
-- 背景：pj_perf_fact / pj_perf_received_apply / pj_received_contract /
--       pj_commission_item 等业务表的 biz_type 一律按贝壳 Excel 原值落中文
--       （PerformanceEngine.BIZ_TYPE_NEW_HOUSE = '一手房'），
--       而折算规则种子误用英文码 'FIRST_HAND'，导致 ConversionFactorAdapter
--       按中文 bizType 永远命不中 0.9024，一手房被错误地按 DEFAULT 0.96 折算
--       （综合查询/新签/实收/结佣各页「折算后」金额与汇总条均偏大）。
-- 修复：规则 key 对齐为中文 '一手房'；DEFAULT 保留（适配器空值/未知类型兜底）。
-- ============================================================
UPDATE pj_payroll_conversion_rule
SET biz_type = '一手房'
WHERE biz_type = 'FIRST_HAND';
