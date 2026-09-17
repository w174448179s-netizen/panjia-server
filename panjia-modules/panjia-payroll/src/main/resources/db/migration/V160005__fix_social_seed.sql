-- =====================================================================
-- V160005 修正社保种子口径（对齐 2026-08 手工工资表实测）
--
-- 实测口径（天街 8 月工资表，59 人）：
--   1. 社保档位与职级无关、按人核定：同为 A2 有 0 / 70% 档 1141.40 / 100% 档
--      1630.57 / 固定 477.15 等多种取值 → 个人差异走 EMPLOYEE 级 policy 覆盖
--      （employeeOverride.{工号}.socialFee 固定额），引擎已支持三级取值。
--   2. 全局默认档（职级缺省）：
--      - 基数 baseSocial 1637.15 → 1630.57（手工 70% 档 1141.40 = 1630.57×0.70）
--      - A 系（A0–A5）个人承担 70%
--      - C 系（C0–C3）个人不扣（公司承担），个例走员工覆盖
--      - S 系（S1/S2）固定 477.15 → 走 socialFixedFee 固定额
--      - D（总监）暂无实测样本，维持 30% 比例不动
--   3. 职级固定额经 policy.socialFixedFee 表达，优先级介于员工覆盖与比例之间。
--
-- 本脚本只改 GLOBAL 种子行，不动 EMPLOYEE 级覆盖。
-- =====================================================================

UPDATE pj_payroll_policy_rule
SET base_social = 1630.57,
    rule_content = jsonb_set(
        jsonb_set(
            rule_content,
            '{socialSettlementRatio}',
            '{"A0":0.70,"A1":0.70,"A2":0.70,"A3":0.70,"A4":0.70,"A5":0.70,
              "C0":0,"C1":0,"C2":0,"C3":0,
              "S1":0,"S2":0,
              "D":0.30}'::jsonb
        ),
        '{socialFixedFee}',
        '{"S1":477.15,"S2":477.15}'::jsonb
    ),
    update_time = NOW()
WHERE scope_type = 'GLOBAL'
  AND (base_social <> 1630.57
       OR rule_content #>> '{socialSettlementRatio,A2}' IS DISTINCT FROM '0.70');
