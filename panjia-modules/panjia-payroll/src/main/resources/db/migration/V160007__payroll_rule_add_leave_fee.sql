-- ============================================================
-- 薪酬域 V160007：考勤规则补充「请假每日扣款额 leaveFee」
--
-- 规则口径：请假扣款 = 请假天数（事假+病假合计）× leaveFee（固定值，与底薪无关）。
-- 现存 GLOBAL 规则的 attendance 配置缺 leaveFee，引擎默认 50；
-- 本迁移为存量规则显式写入 50，保证规则配置与默认口径一致。
-- 幂等：仅在 attendance 存在且 leaveFee 缺失时写入，不覆盖运行期已修改的值。
-- ============================================================

BEGIN;

UPDATE pj_payroll_policy_rule
SET rule_content = jsonb_set(rule_content, '{attendance,leaveFee}', '50'::jsonb, true),
    update_time  = NOW()
WHERE scope_type = 'GLOBAL'
  AND rule_content ? 'attendance'
  AND NOT (rule_content->'attendance' ? 'leaveFee');

COMMIT;
