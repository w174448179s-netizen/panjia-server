-- ============================================================
-- V110010: 员工职级字典清理（客户最新职级口径 2026-10）
-- 保留：A0/A1/A2/A3、S1/S2、D、C1/C2/C3，新增历史职级 B1/B2/B3
-- 清理：A4/A5/C0（客户口径已废弃，新系统上线+人员导入统一按本清单）
-- ============================================================

-- 1. 删除废弃职级
DELETE FROM sys_dict_data
WHERE dict_type = 'panjia_employee_level'
  AND dict_value IN ('A4', 'A5', 'C0');

-- 2. 补充历史职级 B1/B2/B3（历史提成点 30%/40%/50%，规则在 payroll 侧维护）
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, list_class, is_default, create_dept, create_by, create_time)
VALUES
(1761600000000010014, 11, 'B1', 'B1', 'panjia_employee_level', 'default', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010015, 12, 'B2', 'B2', 'panjia_employee_level', 'default', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010016, 13, 'B3', 'B3', 'panjia_employee_level', 'default', 'N', 1761000000000000100, 1761100000000000001, now())
ON CONFLICT (dict_code) DO NOTHING;

-- 3. 统一排序（A 系 → S 系 → C 系 → D → B 系历史）
UPDATE sys_dict_data SET dict_sort = 1  WHERE dict_type = 'panjia_employee_level' AND dict_value = 'A0';
UPDATE sys_dict_data SET dict_sort = 2  WHERE dict_type = 'panjia_employee_level' AND dict_value = 'A1';
UPDATE sys_dict_data SET dict_sort = 3  WHERE dict_type = 'panjia_employee_level' AND dict_value = 'A2';
UPDATE sys_dict_data SET dict_sort = 4  WHERE dict_type = 'panjia_employee_level' AND dict_value = 'A3';
UPDATE sys_dict_data SET dict_sort = 5  WHERE dict_type = 'panjia_employee_level' AND dict_value = 'S1';
UPDATE sys_dict_data SET dict_sort = 6  WHERE dict_type = 'panjia_employee_level' AND dict_value = 'S2';
UPDATE sys_dict_data SET dict_sort = 7  WHERE dict_type = 'panjia_employee_level' AND dict_value = 'C1';
UPDATE sys_dict_data SET dict_sort = 8  WHERE dict_type = 'panjia_employee_level' AND dict_value = 'C2';
UPDATE sys_dict_data SET dict_sort = 9  WHERE dict_type = 'panjia_employee_level' AND dict_value = 'C3';
UPDATE sys_dict_data SET dict_sort = 10 WHERE dict_type = 'panjia_employee_level' AND dict_value = 'D';
UPDATE sys_dict_data SET dict_sort = 11 WHERE dict_type = 'panjia_employee_level' AND dict_value = 'B1';
UPDATE sys_dict_data SET dict_sort = 12 WHERE dict_type = 'panjia_employee_level' AND dict_value = 'B2';
UPDATE sys_dict_data SET dict_sort = 13 WHERE dict_type = 'panjia_employee_level' AND dict_value = 'B3';
