-- ============================================================
-- V160009: 职级/提成规则清理（客户最新职级口径 2026-10 生效）
-- 新口径（2026-10-01 起）：
--   A0=2500底薪+2000绩效+20%提点  A1=55%  A2=60%  A3=70%
--   C1=60%(不买社保)  C2=67%  C3=69%
-- 历史提成点（保留可追溯）：B1=30%  B2=40%  B3=50%
-- 保留不动：S1/S2/D
-- 清理：A4/A5/C0（客户口径废弃）；旧版 A/C 系规则失效日收口到 2026-09-30，
--        与新版本衔接（引擎按算薪日命中生效区间，同职级区间不重叠）
-- ============================================================

-- 1. 删除废弃职级规则
DELETE FROM pj_payroll_rank_rule WHERE level_code IN ('A4', 'A5', 'C0');

-- 2. 旧版 A/C 系规则收口：生效至 2026-09-30（仅处理仍是开口区间的种子行，幂等）
UPDATE pj_payroll_rank_rule
SET effective_to = DATE '2026-09-30'
WHERE level_code IN ('A0', 'A1', 'A2', 'A3', 'C1', 'C2', 'C3')
  AND effective_from = DATE '2026-01-01'
  AND effective_to = DATE '9999-12-31';

-- 3. 新版规则（2026-10-01 起）：沿用旧行 rule_content/team_rate/personal_rate，仅替换底薪与提点
--    A0 附带 performanceSalary=2000 元绩效配置
INSERT INTO pj_payroll_rank_rule (id, level_code, base_salary, base_rate, min_salary, team_rate, personal_rate, rule_content, effective_from)
SELECT 1762500000000000151, 'A0', 2500.00, 0.20, 0, o.team_rate, o.personal_rate,
       o.rule_content || '{"performanceSalary":2000}'::jsonb, DATE '2026-10-01'
FROM pj_payroll_rank_rule o
WHERE o.level_code = 'A0' AND o.effective_from = DATE '2026-01-01'
ON CONFLICT (id) DO NOTHING;

INSERT INTO pj_payroll_rank_rule (id, level_code, base_salary, base_rate, min_salary, team_rate, personal_rate, rule_content, effective_from)
SELECT 1762500000000000152, 'A1', 0, 0.55, 0, o.team_rate, o.personal_rate, o.rule_content, DATE '2026-10-01'
FROM pj_payroll_rank_rule o
WHERE o.level_code = 'A1' AND o.effective_from = DATE '2026-01-01'
ON CONFLICT (id) DO NOTHING;

INSERT INTO pj_payroll_rank_rule (id, level_code, base_salary, base_rate, min_salary, team_rate, personal_rate, rule_content, effective_from)
SELECT 1762500000000000153, 'A2', 0, 0.60, 0, o.team_rate, o.personal_rate, o.rule_content, DATE '2026-10-01'
FROM pj_payroll_rank_rule o
WHERE o.level_code = 'A2' AND o.effective_from = DATE '2026-01-01'
ON CONFLICT (id) DO NOTHING;

INSERT INTO pj_payroll_rank_rule (id, level_code, base_salary, base_rate, min_salary, team_rate, personal_rate, rule_content, effective_from)
SELECT 1762500000000000154, 'A3', 0, 0.70, 0, o.team_rate, o.personal_rate, o.rule_content, DATE '2026-10-01'
FROM pj_payroll_rank_rule o
WHERE o.level_code = 'A3' AND o.effective_from = DATE '2026-01-01'
ON CONFLICT (id) DO NOTHING;

INSERT INTO pj_payroll_rank_rule (id, level_code, base_salary, base_rate, min_salary, team_rate, personal_rate, rule_content, effective_from)
SELECT 1762500000000000155, 'C1', 0, 0.60, 0, o.team_rate, o.personal_rate, o.rule_content, DATE '2026-10-01'
FROM pj_payroll_rank_rule o
WHERE o.level_code = 'C1' AND o.effective_from = DATE '2026-01-01'
ON CONFLICT (id) DO NOTHING;

INSERT INTO pj_payroll_rank_rule (id, level_code, base_salary, base_rate, min_salary, team_rate, personal_rate, rule_content, effective_from)
SELECT 1762500000000000156, 'C2', 0, 0.67, 0, o.team_rate, o.personal_rate, o.rule_content, DATE '2026-10-01'
FROM pj_payroll_rank_rule o
WHERE o.level_code = 'C2' AND o.effective_from = DATE '2026-01-01'
ON CONFLICT (id) DO NOTHING;

INSERT INTO pj_payroll_rank_rule (id, level_code, base_salary, base_rate, min_salary, team_rate, personal_rate, rule_content, effective_from)
SELECT 1762500000000000157, 'C3', 0, 0.69, 0, o.team_rate, o.personal_rate, o.rule_content, DATE '2026-10-01'
FROM pj_payroll_rank_rule o
WHERE o.level_code = 'C3' AND o.effective_from = DATE '2026-01-01'
ON CONFLICT (id) DO NOTHING;

-- 4. 历史提成点职级 B1/B2/B3（2026-01-01 起全程可命中，用于历史月份算薪）
INSERT INTO pj_payroll_rank_rule (id, level_code, base_salary, base_rate, min_salary, team_rate, personal_rate, rule_content, effective_from) VALUES
(1762500000000000158, 'B1', 0, 0.30, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000159, 'B2', 0, 0.40, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000160, 'B3', 0, 0.50, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01')
ON CONFLICT (id) DO NOTHING;

-- 5. 全局政策 socialSettlementRatio 对齐新职级清单：
--    移除 A4/A5/C0；B 系历史全职按 A 系口径 0.70；C 系不买社保保持 0
UPDATE pj_payroll_policy_rule
SET rule_content = jsonb_set(
    rule_content,
    '{socialSettlementRatio}',
    '{"A0":0.70,"A1":0.70,"A2":0.70,"A3":0.70,
      "C1":0,"C2":0,"C3":0,
      "S1":0,"S2":0,
      "D":0.30,
      "B1":0.70,"B2":0.70,"B3":0.70}'::jsonb
)
WHERE scope_type = 'GLOBAL';
