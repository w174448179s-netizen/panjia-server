-- ============================================================
-- V160015: 工资明细增加所有扣点合计字段（前端「绩效提成扣点」列统一展示）
--
-- 背景：
--   原 perfDeduct 仅存积分等级扣点，manualAdjust 存未参保自动扣点 + 人工审批项，
--   前端多处「绩效提成扣点」列只取 perfDeduct，漏了未买社保扣点和人工调整。
--   新增 totalDeduct = perfDeduct + manualAdjust（引擎算完两值后赋值），
--   前端 batch 列表、导出 Excel、详情页三处统一取此值，finalRate 逻辑不动。
--   perfDeduct / manualAdjust 字段保留，PayrollTracePanel 仍可按需展示分项明细。
-- ============================================================

ALTER TABLE pj_payroll_detail
    ADD COLUMN IF NOT EXISTS total_deduct NUMERIC(8,6) NOT NULL DEFAULT 0;

COMMENT ON COLUMN pj_payroll_detail.total_deduct IS '所有扣点合计（等级扣点+未参保自动扣点+人工调整，负=扣点，前端绩效提成扣点列统一取此值）';
