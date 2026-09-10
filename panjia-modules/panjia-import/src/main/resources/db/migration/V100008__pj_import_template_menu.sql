-- ============================================================
-- 盘家智管 · 导入域 · 模板管理菜单 + 按钮权限
-- 新增菜单：模板管理（数据导入目录下，排序 5）
-- 新增按钮权限：列表/新增/编辑/激活
-- ============================================================

BEGIN;

-- 1. 模板管理菜单（页面级，parent = 数据导入目录 1761400000000002100）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002105, '模板管理', 1761400000000002100, 5, 'template', 'import/template/index', NULL, 'N', 'Y', 'C', '0', '0', 'import:template:list', 'documentation', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '导入模板管理（查看/复制/激活/下载）');

-- 2. 按钮权限
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002110, '模板新增', 1761400000000002105, 1, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'import:template:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '新增/复制模板');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002111, '模板编辑', 1761400000000002105, 2, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'import:template:edit', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '编辑模板列映射');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002112, '模板激活', 1761400000000002105, 3, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'import:template:activate', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '激活/停用模板');

-- 3. 角色菜单关联
-- 管理员（1761300000000000012）拥有模板管理全部权限
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000012, 1761400000000002105),
(1761300000000000012, 1761400000000002110),
(1761300000000000012, 1761400000000002111),
(1761300000000000012, 1761400000000002112);

COMMIT;
