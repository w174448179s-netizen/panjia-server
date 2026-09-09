-- ============================================================
-- Rollback: V100013 员工模块菜单 / 角色 / 权限对齐
-- ============================================================

BEGIN;

-- 1. 回滚角色-菜单绑定（人事的 5 个 F 按钮）
DELETE FROM sys_role_menu WHERE menu_id IN (
    1761400000000002004, 1761400000000002005, 1761400000000002006,
    1761400000000002007, 1761400000000002008
);

-- 2. 回滚 F 按钮菜单
DELETE FROM sys_menu WHERE menu_id IN (
    1761400000000002004, 1761400000000002005, 1761400000000002006,
    1761400000000002007, 1761400000000002008
);

-- 3. 回滚角色映射（恢复 V100012 的占位 ID）
UPDATE pj_people_role_mapping SET sys_role_id = 100 WHERE id = 12001;  -- AGENT
UPDATE pj_people_role_mapping SET sys_role_id = 110 WHERE id = 12002;  -- STORE_MANAGER
UPDATE pj_people_role_mapping SET sys_role_id = 130 WHERE id = 12003;  -- STORE_MANAGER
UPDATE pj_people_role_mapping SET sys_role_id = 120 WHERE id = 12004;  -- DIRECTOR
UPDATE pj_people_role_mapping SET sys_role_id = 130 WHERE id = 12005;  -- DIRECTOR

COMMIT;
