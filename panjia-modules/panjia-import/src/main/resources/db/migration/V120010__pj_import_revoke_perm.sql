-- ============================================================================
-- V120010 导入撤销权限补建
-- 新增 import:batch:revoke 按钮权限，挂在「数据导入」菜单下。
-- ============================================================================

BEGIN;

-- 撤销导入（硬删批次及下游数据，原始导入文件保留）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000011814, '撤销导入', 1761400000000002100, 14, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'import:batch:revoke', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '撤销已归档的导入批次，硬删下游数据');

-- 超级管理员
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1, 1761400000000011814);

-- 财务角色
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000012, 1761400000000011814);

COMMIT;
