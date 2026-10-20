-- ============================================================
-- V110015: 员工状态新增「虚拟」(VIRTUAL)
--   * 字典 panjia_employee_status 追加 VIRTUAL=虚拟
--   * 存量虚拟员工（工号 = 店组编码 sys_dept.dept_category）状态从 ACTIVE 迁移为 VIRTUAL
--   * 新增虚拟人由 initVirtualEmployees 直接写入 VIRTUAL 状态
--   * 算薪 SQL 已用 status IN ('ACTIVE','PARTTIME') 天然排除 VIRTUAL；
--     员工列表默认筛选不含 VIRTUAL，虚拟人默认不展示
-- ============================================================

BEGIN;

-- 1. 字典追加「虚拟」
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, create_by, create_time)
VALUES (1761600000000040005, 5, '虚拟', 'VIRTUAL', 'panjia_employee_status', '', 'info', 'N', 1761000000000000100, now())
ON CONFLICT (dict_code) DO NOTHING;

-- 2. 存量虚拟人状态迁移：工号 = 店组编码 的员工 → VIRTUAL
UPDATE pj_people_employee e
SET status = 'VIRTUAL', update_time = now()
FROM sys_dept d
WHERE e.dept_id = d.dept_id
  AND e.employee_code = d.dept_category
  AND d.dept_category IS NOT NULL
  AND d.dept_category <> ''
  AND e.status <> 'VIRTUAL';

COMMIT;
