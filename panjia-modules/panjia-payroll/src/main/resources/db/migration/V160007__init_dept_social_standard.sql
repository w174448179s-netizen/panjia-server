-- ===================================================================
-- V160007: 门店社保扣减标准初始值
-- 口径：DEPT 政策 rule_content.socialStandard = 每人每月社保扣减标准，
--       算薪时 门店社保业绩扣款 = 标准 × 计缴参保人数（非兼职+参保+个人比例>30%）
-- 初始值：锦城名都店 1637.91，其余门店 1628.74
-- 说明：按部门名定位门店（不硬编码 deptId），幂等——已存在当期有效
--       DEPT 政策的门店仅更新值，不存在才插入；未来新增门店在规则页配置
-- ===================================================================

-- ---------- 1) 锦城名都店：1637.91 ----------
UPDATE pj_payroll_policy_rule p
SET rule_content = '{"socialStandard": 1637.91}'::jsonb,
    base_social  = 1637.15,
    update_time  = now()
FROM sys_dept d
WHERE d.dept_id::text = p.scope_key
  AND p.scope_type = 'DEPT'
  AND d.dept_name = '锦城名都店'
  AND d.del_flag = '0' AND d.status = '0'
  AND p.effective_from <= CURRENT_DATE
  AND p.effective_to >= CURRENT_DATE;

INSERT INTO pj_payroll_policy_rule (id, scope_type, scope_key, base_social, rule_content, effective_from, effective_to)
SELECT 2108070000000000001, 'DEPT', d.dept_id::text, 1637.15,
       '{"socialStandard": 1637.91}'::jsonb, DATE '2026-10-01', DATE '9999-12-31'
FROM sys_dept d
WHERE d.dept_name = '锦城名都店'
  AND d.del_flag = '0' AND d.status = '0'
  AND NOT EXISTS (
    SELECT 1 FROM pj_payroll_policy_rule p
    WHERE p.scope_type = 'DEPT'
      AND p.scope_key = d.dept_id::text
      AND p.effective_from <= CURRENT_DATE
      AND p.effective_to >= CURRENT_DATE
  );

-- ---------- 2) 其余门店：1628.74（根部门直接子部门，排除锦城名都店） ----------
UPDATE pj_payroll_policy_rule p
SET rule_content = '{"socialStandard": 1628.74}'::jsonb,
    base_social  = 1637.15,
    update_time  = now()
FROM sys_dept d
WHERE d.dept_id::text = p.scope_key
  AND p.scope_type = 'DEPT'
  AND d.ancestors = '0,' || (SELECT dept_id FROM sys_dept WHERE parent_id = 0 LIMIT 1)
  AND d.dept_name <> '锦城名都店'
  AND d.del_flag = '0' AND d.status = '0'
  AND p.effective_from <= CURRENT_DATE
  AND p.effective_to >= CURRENT_DATE;

INSERT INTO pj_payroll_policy_rule (id, scope_type, scope_key, base_social, rule_content, effective_from, effective_to)
SELECT 2108070000000000010 + row_number() OVER (ORDER BY d.order_num, d.dept_id),
       'DEPT', d.dept_id::text, 1637.15,
       '{"socialStandard": 1628.74}'::jsonb, DATE '2026-10-01', DATE '9999-12-31'
FROM sys_dept d
WHERE d.ancestors = '0,' || (SELECT dept_id FROM sys_dept WHERE parent_id = 0 LIMIT 1)
  AND d.dept_name <> '锦城名都店'
  AND d.del_flag = '0' AND d.status = '0'
  AND NOT EXISTS (
    SELECT 1 FROM pj_payroll_policy_rule p
    WHERE p.scope_type = 'DEPT'
      AND p.scope_key = d.dept_id::text
      AND p.effective_from <= CURRENT_DATE
      AND p.effective_to >= CURRENT_DATE
  );
