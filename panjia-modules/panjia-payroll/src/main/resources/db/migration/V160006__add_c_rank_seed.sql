-- V160006: 补充 C 系职级（新人序列）与 C0 新人保护底薪
-- 依据 2026-08 天街手工工资表真实体系（C0-C3/A1-A3/S1-S2）：
--   C0 提点 0.30 / 底薪 2000（新人保护期）
--   C1 提点 0.58（底薪为个人属性，不设职级默认，避免误发）
--   C2 提点 0.68 / C3 提点 0.70（成熟经纪人）
-- 注：仅新增 C 系行，不改动既有 A/S/D 职级。

INSERT INTO pj_payroll_rank_rule (id, level_code, base_salary, base_rate, min_salary, team_rate, personal_rate, rule_content, effective_from) VALUES
(1762500000000000111, 'C0', 2000.00, 0.30, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000112, 'C1', 0, 0.58, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000113, 'C2', 0, 0.68, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000114, 'C3', 0, 0.70, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01')
ON CONFLICT (id) DO NOTHING;
