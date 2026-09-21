


delete from pj_payroll_rank_rule;
-- 职级/提成规则
INSERT INTO pj_payroll_rank_rule (id, level_code, base_salary, base_rate, min_salary, team_rate, personal_rate, rule_content, effective_from) VALUES
(1762500000000000101, 'A0', 4500.00, 0.55, 4500.00, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000102, 'A1', 0, 0.6, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000103, 'A2', 0, 0.7, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000104, 'A3', 0, 0.7, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000105, 'A4', 0, 0.7, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000106, 'A5', 0, 0.70, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000107, 'S1', 0, 0.30, 8000.00, 0.10, 0.70, '{"mentorBonusMode":"AMOUNT_RATIO","mentorBonusAmountRatio":0.02}', '2026-01-01'),
(1762500000000000108, 'S2', 0, 0.30, 8000.00, 0.10, 0.70, '{"mentorBonusMode":"AMOUNT_RATIO","mentorBonusAmountRatio":0.02}', '2026-01-01'),
(1762500000000000109, 'D',  6000.00, 0.30, 0, NULL, NULL, '{"brackets":[{"min":0,"max":100000,"rate":0.06},{"min":100000,"max":200000,"rate":0.07},{"min":200000,"max":null,"rate":0.08}],"mentorBonusMode":"AMOUNT_RATIO","mentorBonusAmountRatio":0.02}', '2026-01-01'),
(1762500000000000111, 'C0', 2000.00, 0.30, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000112, 'C1', 0, 0.6, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000113, 'C2', 0, 0.7, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000114, 'C3', 0, 0.70, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01')

