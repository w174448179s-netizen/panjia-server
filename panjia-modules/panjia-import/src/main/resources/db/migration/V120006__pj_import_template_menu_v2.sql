-- ============================================================
-- 盘家智管 · 模板管理 V2 · 移至系统管理 + 员工导入模板 + 列编辑 + 版本对比
-- 段位：V120006（2026-09-11 由 V100009 重命名）
-- 变更：
--   1) 删除旧模板管理菜单（原挂在数据导入下，menu_id=2105）— 该旧菜单曾由 V100008 创建，
--      现已删除该脚本（V100008 被 V100009 完全替代）。
--   2) 新模板管理菜单挂在系统管理目录下，统一管理单据导入+员工导入模板
--   3) 新增 6 个按钮权限（3个单据导入 + 3个员工导入）
--   4) 更新角色菜单关联（超管 + 财务总监拥有全部模板管理权限）
-- ============================================================

BEGIN;

-- ========== 1. 删除旧菜单及角色关联 ==========
DELETE FROM sys_role_menu WHERE menu_id IN (
  1761400000000002105,
  1761400000000002110,
  1761400000000002111,
  1761400000000002112
);

DELETE FROM sys_menu WHERE menu_id IN (
  1761400000000002105,
  1761400000000002110,
  1761400000000002111,
  1761400000000002112
);

-- ========== 2. 新菜单：模板管理（挂在系统管理目录 1761400000000000001 下） ==========
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002510, '模板管理', 1761400000000000001, 80, 'template', 'import/template/index', NULL, 'N', 'Y', 'C', '0', '0', 'import:template:list', 'documentation', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '导入模板管理（单据导入+员工导入，可视化编辑/版本对比/复制激活）');

-- 单据导入模板按钮
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002511, '模板新增', 1761400000000002510, 1, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'import:template:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '新增/复制导入模板');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002512, '模板编辑', 1761400000000002510, 2, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'import:template:edit', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '编辑导入模板列映射');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002513, '模板激活', 1761400000000002510, 3, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'import:template:activate', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '激活/停用导入模板');

-- 员工导入模板按钮
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002517, '员工模板列表', 1761400000000002510, 3, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'people:template:list', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '查看员工导入模板列表');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002514, '员工模板新增', 1761400000000002510, 4, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'people:template:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '新增/复制员工导入模板');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002515, '员工模板编辑', 1761400000000002510, 5, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'people:template:edit', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '编辑员工导入模板列定义');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002516, '员工模板启用', 1761400000000002510, 6, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'people:template:activate', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '启用/停用员工导入模板');

-- ========== 3. 角色菜单关联 ==========
-- 超级管理员（role_id=1）拥有全部权限
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1, 1761400000000002510),
(1, 1761400000000002511),
(1, 1761400000000002512),
(1, 1761400000000002513),
(1, 1761400000000002517),
(1, 1761400000000002514),
(1, 1761400000000002515),
(1, 1761400000000002516);

-- 总监/管理员角色（1761300000000000012）拥有全部模板管理权限
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000012, 1761400000000002510),
(1761300000000000012, 1761400000000002511),
(1761300000000000012, 1761400000000002512),
(1761300000000000012, 1761400000000002513),
(1761300000000000012, 1761400000000002517),
(1761300000000000012, 1761400000000002514),
(1761300000000000012, 1761400000000002515),
(1761300000000000012, 1761400000000002516);

COMMIT;
