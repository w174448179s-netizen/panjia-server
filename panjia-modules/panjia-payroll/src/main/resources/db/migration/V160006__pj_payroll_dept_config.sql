-- ============================================================
-- 门店级算薪配置（V160006）
-- 1. 门店月度配置表：新签与结佣差额按「门店 × 月份」维护，算薪直接扣减配置值
--    （替代原前端「新签团队业绩 − 结佣业绩」的现算公式）
-- 2. pj_payroll_detail 增加门店社保标准 / 计缴参保人数 / 新签与结佣差额落地列
-- 3. 门店社保扣减标准走 pj_payroll_policy_rule（scope_type='DEPT'，
--    rule_content.socialStandard，每人每月固定额），无需新表
-- ============================================================

-- 1. 门店月度配置（新签与结佣差额；后续门店级月度扣减项也并入此表）
CREATE TABLE pj_payroll_dept_monthly_config (
    id            BIGINT        PRIMARY KEY,
    dept_id       BIGINT        NOT NULL,
    period        VARCHAR(7)    NOT NULL,
    diff_amount   NUMERIC(18,2) NOT NULL DEFAULT 0,
    remark        VARCHAR(512),
    version       INT           NOT NULL DEFAULT 0,
    create_time   TIMESTAMP     NOT NULL DEFAULT NOW(),
    update_time   TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uk_pdeptmonthly_dept_period ON pj_payroll_dept_monthly_config(dept_id, period);

COMMENT ON TABLE pj_payroll_dept_monthly_config IS '门店月度算薪配置（新签与结佣差额等，按门店×月份维护，算薪直接扣减）';
COMMENT ON COLUMN pj_payroll_dept_monthly_config.diff_amount IS '新签与结佣差额（团队计薪业绩直接扣减项，未配置按0）';

-- 2. 工资明细落地列（店长/总监 sheet 展示）
ALTER TABLE pj_payroll_detail ADD COLUMN dept_social_standard NUMERIC(18,2) DEFAULT 0;
ALTER TABLE pj_payroll_detail ADD COLUMN dept_insured_count    INT           DEFAULT 0;
ALTER TABLE pj_payroll_detail ADD COLUMN dept_diff_amount      NUMERIC(18,2) DEFAULT 0;

COMMENT ON COLUMN pj_payroll_detail.dept_employer_social_total IS '门店社保业绩扣款（门店社保扣减标准 × 计缴参保人数）';
COMMENT ON COLUMN pj_payroll_detail.dept_social_standard IS '门店社保扣减标准（每人每月固定额，DEPT 政策 socialStandard）';
COMMENT ON COLUMN pj_payroll_detail.dept_insured_count IS '门店计缴参保人数（非兼职+参保+个人社保比例>30%，按门店及下属组别人数合计）';
COMMENT ON COLUMN pj_payroll_detail.dept_diff_amount IS '新签与结佣差额（门店当月配置值，团队计薪业绩直接扣减）';
