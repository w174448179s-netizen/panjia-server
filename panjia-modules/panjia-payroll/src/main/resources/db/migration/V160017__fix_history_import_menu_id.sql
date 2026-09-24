-- ============================================================
-- V160017: 修复历史工资导入菜单 ID 冲突
--
-- 问题：V160016 误用了 menu_id 1761400000000002320/2321，
--       该 ID 已被「提成点调整」菜单占用（V100028）。
--       ON CONFLICT DO NOTHING 导致菜单静默未创建，
--       但超管的 role_menu 错误授权（指向提成点调整）已插入。
--
-- 修复：
--   1. 删除超管→提成点调整菜单的错误授权（不影响财务/总监的正常授权）；
--   2. 用空闲 ID 2323（C 菜单）/ 2324（F 按钮）重新插入；
--   3. 仅授予超级管理员（role_id=1761300000000000001）。
-- ============================================================

BEGIN;

-- 1. 清理 V160016 插入的错误授权（仅超管角色，不动财务/总监对提成点调整的授权）
DELETE FROM sys_role_menu
WHERE role_id = 1761300000000000001
  AND menu_id IN (1761400000000002320, 1761400000000002321);

-- 2. 历史工资导入 C 菜单（挂在「薪酬计算」2300 目录下）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761400000000002323, '历史工资导入', 1761400000000002300, 20, 'history-import', 'payroll/history-import/index', NULL,
 'N', 'Y', 'C', '0', '0', 'payroll:history:import', 'upload', '', '',
 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '历史工资 Excel 导入（仅超级管理员）')
ON CONFLICT (menu_id) DO NOTHING;

-- 3. 导入按钮 F 权限码
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761400000000002324, '导入', 1761400000000002323, 1, '', NULL, NULL,
 'N', 'Y', 'F', '0', '0', 'payroll:history:import', '#', '', '',
 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '上传 Excel 导入历史工资')
ON CONFLICT (menu_id) DO NOTHING;

-- 4. 仅授予超级管理员（含父目录「薪酬计算」2300——菜单树要求父级可见）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000001, 1761400000000002300),  -- 超级管理员 → 薪酬计算目录
(1761300000000000001, 1761400000000002323),  -- 超级管理员 → 历史工资导入菜单
(1761300000000000001, 1761400000000002324)   -- 超级管理员 → 导入按钮
ON CONFLICT (role_id, menu_id) DO NOTHING;

COMMIT;
