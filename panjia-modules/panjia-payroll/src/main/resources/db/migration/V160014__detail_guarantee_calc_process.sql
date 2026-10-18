-- pj_payroll_detail 新增 guarantee_calc_process：保底补足计算过程（保底线/比较基准/分笔构成/补足结果）
-- 仅保底职级（min_salary > 0）落地；未触发保底也落地（fill=0），供前端展示"有没有触发、怎么算的"
ALTER TABLE pj_payroll_detail
    ADD COLUMN IF NOT EXISTS guarantee_calc_process text;

COMMENT ON COLUMN pj_payroll_detail.guarantee_calc_process IS
    '保底补足计算过程 JSON {minSalary,compareLabel,compareValue,steps?,newsignSteps?,fill}';
