-- ===================================================================
-- V160013: 结佣提成计算过程透明化
-- pj_payroll_detail 新增 commission_calc_process：按新签导入月分组的分笔计算式
-- JSON 数组 [{month:"2026-08",perf:26723.50,rate:0.70,income:18706.45},...]
-- 仅存在跨月回溯（新签导入月 ≠ 算薪月）时落地，供工资明细/批次导出展示
-- ===================================================================

ALTER TABLE pj_payroll_detail
    ADD COLUMN IF NOT EXISTS commission_calc_process text;

COMMENT ON COLUMN pj_payroll_detail.commission_calc_process IS '结佣提成计算过程 JSON，仅跨月回溯时落地';
