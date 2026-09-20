-- ============================================================
-- V160013: 工资明细增加总监各门店提成明细 JSON 列
--
-- 背景：
--   总监管辖多门店，天街工资表总监 sheet 按门店分行展示（每个门店一行：
--   新签业绩/社保业绩/合计/跳点档/提成金额），提成金额汇总到第一行发工资。
--   原先 PayrollDetail 一人一行无法承载多门店明细，新增 JSON 列存各门店
--   提成明细（deptId/newSign/social/billable/rate/income），导出时还原分行。
--
-- 口径：
--   storeIncome = Σ 各门店 (newSign - social) × 跳点档 rate
--   directorStoreItems = [{deptId,newSign,social,billable,rate,income}, ...]
-- ============================================================

ALTER TABLE pj_payroll_detail
    ADD COLUMN IF NOT EXISTS director_store_items TEXT;

COMMENT ON COLUMN pj_payroll_detail.director_store_items IS '总监各门店提成明细 JSON（deptId/newSign/social/billable/rate/income，导出按门店分行）';
