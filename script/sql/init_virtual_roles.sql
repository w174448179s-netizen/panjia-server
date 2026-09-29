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
DO $$
DECLARE
    v_fu_fang_id BIGINT;
    v_dept_id    BIGINT;
    v_ancestors  VARCHAR(500);
    r            RECORD;
BEGIN
    SELECT dept_id INTO v_fu_fang_id FROM sys_dept WHERE parent_id = 0 AND dept_name = '富房' AND del_flag = '0' LIMIT 1;
    IF v_fu_fang_id IS NULL THEN
        RAISE EXCEPTION '未找到「富房」顶级部门，请先在系统管理-部门管理创建';
    END IF;

    FOR r IN
        SELECT * FROM (VALUES
            ('CD_15_1696157', '西派少城店 A 组'),
            ('CD_15_133971',  '花照云庭店 A 组'),
            ('CD_15_1645553', '锦城名都店 A 组'),
            ('CD_15_133975',  '花照云庭店 B 组'),
            ('CD_15_111073',  '龙湖店 A 组'),
            ('CD_15_1673298', '长庆店 A 组')
        ) AS t(code, name)
    LOOP
        -- 按 dept_category 查已有部门
        SELECT dept_id, ancestors INTO v_dept_id, v_ancestors
        FROM sys_dept WHERE dept_category = r.code AND del_flag = '0' LIMIT 1;

        IF v_dept_id IS NULL THEN
            -- 新建：雪花 ID 用随机 bigint 近似
            v_dept_id := (random() * 9223372036854775807)::bigint;
            v_ancestors := '0,' || v_fu_fang_id;
            INSERT INTO sys_dept (dept_id, parent_id, ancestors, dept_name, dept_category,
                                  order_num, status, del_flag, create_time)
            VALUES (v_dept_id, v_fu_fang_id, v_ancestors, r.name, r.code,
                    0, '0', '0', now());
            RAISE NOTICE '新建店组部门: % (code=%)', r.name, r.code;
        ELSE
            -- 更新名称 + 确保挂到富房下
            UPDATE sys_dept SET dept_name = r.name, parent_id = v_fu_fang_id,
                                ancestors = '0,' || v_fu_fang_id, update_time = now()
            WHERE dept_id = v_dept_id;
            RAISE NOTICE '更新店组部门: % (code=%)', r.name, r.code;
        END IF;
    END LOOP;
END $$;

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

-- ---------- 三、校验输出 ----------
SELECT '店组部门：' || count(*) AS cnt FROM sys_dept
WHERE dept_category IN ('CD_15_1696157','CD_15_133971','CD_15_1645553','CD_15_133975','CD_15_111073','CD_15_1673298') AND del_flag='0';

SELECT '虚拟角色人：' || count(*) AS cnt FROM pj_people_employee
WHERE employee_code IN ('CD_15_1696157','CD_15_133971','CD_15_1645553','CD_15_133975','CD_15_111073','CD_15_1673298');

COMMIT;
