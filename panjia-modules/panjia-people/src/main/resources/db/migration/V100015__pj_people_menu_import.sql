-- ============================================================
-- 补充员工导入按钮菜单及角色绑定
-- 归属域：panjia-people
-- 依赖：V100013（菜单/角色对齐）
-- ============================================================

BEGIN;

-- 员工导入按钮（挂在员工档案 C 菜单下）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002009, '员工导入', 1761400000000002001, 4, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:employee:import', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '员工档案-批量导入按钮')
ON CONFLICT (menu_id) DO NOTHING;

-- 人事角色绑定
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000013, 1761400000000002009)
ON CONFLICT (role_id, menu_id) DO NOTHING;

COMMIT;
