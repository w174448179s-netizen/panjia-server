-- ============================================================
-- V160010: 工资明细增加积分扣款与业绩溯源字段
--
-- 背景：
--   1. 积分扣款（晚提交处罚）重新上线：points_fee 列恢复（V160008 曾下线）。
--   2. 新签业绩、结佣业绩、新签提成比例直接落地到 pj_payroll_detail，
--      避免前端用「提成金额 ÷ 比例」反推产生误差，导出直接取列值。
-- ============================================================

ALTER TABLE pj_payroll_detail
    ADD COLUMN IF NOT EXISTS points_fee NUMERIC(12,2) DEFAULT 0,
    ADD COLUMN IF NOT EXISTS new_sign_performance NUMERIC(14,2) DEFAULT 0,
    ADD COLUMN IF NOT EXISTS commission_performance NUMERIC(14,2) DEFAULT 0,
    ADD COLUMN IF NOT EXISTS new_sign_rate NUMERIC(8,4) DEFAULT 0;

COMMENT ON COLUMN pj_payroll_detail.points_fee IS '积分扣款（晚提交处罚：次数×5元/次）';
COMMENT ON COLUMN pj_payroll_detail.new_sign_performance IS '当月新签业绩（折算后金额；店长=个人新签业绩）';
COMMENT ON COLUMN pj_payroll_detail.commission_performance IS '当月结佣业绩（不折算，贝壳实收到手值）';
COMMENT ON COLUMN pj_payroll_detail.new_sign_rate IS '当月新签业绩提成比例（职级personalRate）';
