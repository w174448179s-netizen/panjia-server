-- ============================================================
-- V160016: 历史工资导入菜单 + 权限码（仅超级管理员）
--
-- 功能：上传历史工资 Excel（7 sheet）回写工资批次+明细+业绩事实
-- 权限：payroll:history:import，仅授予超级管理员（role_id=1761300000000000001）
-- ============================================================

BEGIN;

-- 1. 菜单（C 型页面，挂在「算薪管理」父菜单下）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761400000000002320, '历史工资导入', 1761400000000002300, 20, 'history-import', 'payroll/history-import/index', NULL,
 'N', 'Y', 'C', '0', '0', 'payroll:history:import', 'upload', '', '',
 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '历史工资 Excel 导入（仅超级管理员）')
ON CONFLICT (menu_id) DO NOTHING;

-- 2. 按钮权限码（F 型）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761400000000002321, '导入', 1761400000000002320, 1, '', NULL, NULL,
 'N', 'Y', 'F', '0', '0', 'payroll:history:import', '#', '', '',
 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '上传 Excel 导入历史工资')
ON CONFLICT (menu_id) DO NOTHING;

-- 3. 仅授予超级管理员（role_id=1761300000000000001）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000001, 1761400000000002320),  -- 超级管理员 → 历史工资导入菜单
(1761300000000000001, 1761400000000002321)   -- 超级管理员 → 导入按钮
ON CONFLICT (role_id, menu_id) DO NOTHING;

COMMIT;
