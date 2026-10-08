-- ============================================================================
-- 初始化店组编码 + 虚拟角色人（清库重建后执行一次）
--
-- 背景：贝壳新签行经纪人为空时，按行上店组编码（deptCode）挂靠到对应
--       店组的虚拟角色人。原方案虚拟人用 99999 开头工号识别，本脚本
--       将虚拟人改为「工号 = 店组编码」，判断逻辑同步改为按工号匹配
--       店组编码（见 EmployeeServiceImpl.findVirtualEmployeesByStoreGroups）。
--
-- 6 个店组：
--   CD_15_1696157 富房 - 天街 - 西派少城店 A 组
--   CD_15_133971  富房 - 天街 - 花照云庭店 A 组
--   CD_15_1645553 富房 - 天街 - 锦城名都店 A 组
--   CD_15_133975  富房 - 天街 - 花照云庭店 B 组
--   CD_15_111073  富房 - 天街 - 龙湖店 A 组
--   CD_15_1673298 富房 - 天街 - 长庆店 A 组
--
-- 幂等：部门按 dept_category 唯一、员工按 employee_code 唯一，
--       重复执行只更新不重复插入。
-- ============================================================================

BEGIN;

-- ---------- 一、初始化店组部门（sys_dept） ----------
-- 父部门：富房（顶级，parent_id=0）
  UPDATE sys_dept SET dept_category = 'CD_15_1696157'
  where dept_name = '西派少城店A组';
    UPDATE sys_dept SET dept_category = 'CD_15_133971'
  where dept_name = '花照云庭店A组';
  
    UPDATE sys_dept SET dept_category = 'CD_15_1645553'
  where dept_name = '锦城名都店A组';
      UPDATE sys_dept SET dept_category = 'CD_15_133975'
  where dept_name = '花照云庭店B组';
  
    UPDATE sys_dept SET dept_category = 'CD_15_111073'
  where dept_name = '龙湖店A组';    
    UPDATE sys_dept SET dept_category = 'CD_15_1673298'
  where dept_name = '长庆店A组';        
  
-- ---------- 二、初始化虚拟角色人（pj_people_employee） ----------
-- 工号 = 店组编码；姓名 = 门店名 + 「虚拟人」；归属对应店组部门
INSERT INTO pj_people_employee (employee_id, employee_code, employee_name, dept_id, hire_date, status, version, create_time, update_time)
SELECT
    (random() * 9223372036854775807)::bigint,
    d.dept_category,
    d.dept_name,
    d.dept_id,
    now()::date,
    'ACTIVE',
    0,
    now(),
    now()
FROM sys_dept d
WHERE d.dept_category IN ('CD_15_1696157','CD_15_133971','CD_15_1645553','CD_15_133975','CD_15_111073','CD_15_1673298')
  AND d.del_flag = '0'
  AND NOT EXISTS (
      SELECT 1 FROM pj_people_employee e WHERE e.employee_code = d.dept_category
  );


COMMIT;
