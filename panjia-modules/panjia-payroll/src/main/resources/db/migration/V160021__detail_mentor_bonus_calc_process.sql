-- ===================================================================
-- V160021: 伯乐奖励计算过程透明化
-- pj_payroll_detail 新增 mentor_bonus_calc_process：结佣月口径的计算式
-- JSON 数组 [{"month":"2026-09","base":96000.00,"ratio":0.03,"bonus":2880.00}]
-- month 为结佣月（算薪月），base 为千里马当月折算后结佣业绩，ratio 为本算薪月职级规则配置的比例
-- ===================================================================

ALTER TABLE pj_payroll_detail
    ADD COLUMN IF NOT EXISTS mentor_bonus_calc_process text;

COMMENT ON COLUMN pj_payroll_detail.mentor_bonus_calc_process IS '伯乐奖励计算过程 JSON（结佣月：千里马当月结佣业绩/本算薪月规则比例/金额）';
