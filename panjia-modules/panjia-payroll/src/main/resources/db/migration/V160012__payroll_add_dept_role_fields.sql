-- ============================================================
-- V160012: 工资明细增加店长/总监 sheet 展示字段
--
-- 背景：
--   对齐天街工资表 2026.08.xlsx 三个 sheet 列结构（经纪人 27 列 / 店长 15 列 / 总监 19 列）。
--   店长 sheet 涉及「8月新签团队业绩」「社保业绩扣款」「提成比例」「保底」列，
--   总监 sheet 涉及「新签业绩」「社保业绩」「提成比例」「全勤」列，
--   原先仅参与引擎计算未单独落地，导出/列表需要反推或留空。
--   单独落地后导出与列表直接取列值，避免前端反推误差。
--
-- 口径：
--   teamIncome = (deptNewSignTotal - deptEmployerSocialTotal) × teamRate
--   storeIncome = (deptNewSignTotal - deptEmployerSocialTotal) × storeRate
--   gross += fullAttendance（仅总监，固定 500 元全勤奖）
-- ============================================================

ALTER TABLE pj_payroll_detail
    ADD COLUMN IF NOT EXISTS dept_new_sign_total        NUMERIC(18,2) DEFAULT 0,
    ADD COLUMN IF NOT EXISTS dept_employer_social_total NUMERIC(18,2) DEFAULT 0,
    ADD COLUMN IF NOT EXISTS team_rate                  NUMERIC(9,6),
    ADD COLUMN IF NOT EXISTS store_rate                 NUMERIC(9,6),
    ADD COLUMN IF NOT EXISTS min_salary                 NUMERIC(18,2) DEFAULT 0,
    ADD COLUMN IF NOT EXISTS full_attendance            NUMERIC(18,2) DEFAULT 0;

COMMENT ON COLUMN pj_payroll_detail.dept_new_sign_total        IS '门店当月新签计薪业绩合计（折算后，店长/总监展示用）';
COMMENT ON COLUMN pj_payroll_detail.dept_employer_social_total IS '门店社保业绩扣款（门店全员公司承担社保合计）';
COMMENT ON COLUMN pj_payroll_detail.team_rate                  IS '店长团队提成比例（职级规则 teamRate）';
COMMENT ON COLUMN pj_payroll_detail.store_rate                  IS '总监门店提成比例（跳点命中档 rate）';
COMMENT ON COLUMN pj_payroll_detail.min_salary                  IS '店长保底工资（职级规则 minSalary）';
COMMENT ON COLUMN pj_payroll_detail.full_attendance            IS '总监全勤奖（policy.fullAttendance 默认 500）';
