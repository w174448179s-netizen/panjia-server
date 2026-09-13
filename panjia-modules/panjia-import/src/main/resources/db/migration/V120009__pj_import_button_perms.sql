-- ============================================================================
-- V120009 数据导入操作按钮权限补建
-- 问题：前端导入按钮 v-hasPermi="['import:batch:upload']"，
--       但菜单系统中从未创建该 F 型按钮，导致除超管外所有角色都看不到导入按钮。
-- 同时补齐后端用到的归一化/归档/忽略问题权限。
-- ============================================================================

BEGIN;

-- ========== 1. 新建 F 型按钮菜单（挂在「数据导入」1761400000000002100 下） ==========

-- 上传导入（贝壳业绩/考勤/积分共用同一权限）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000011810, '上传导入', 1761400000000002100, 10, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'import:batch:upload', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '上传导入文件+下载模板');

-- 重新归一化
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000011811, '重新归一化', 1761400000000002100, 11, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'import:batch:renormalize', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '基于原始文件重新解析与归一化');

-- 归档批次
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000011812, '归档批次', 1761400000000002100, 12, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'import:batch:archive', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '归档导入批次');

-- 忽略问题
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000011813, '忽略问题', 1761400000000002100, 13, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'import:issue:ignore', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '忽略导入问题行');

-- ========== 2. 超级管理员（role_id=1） ==========
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1, 1761400000000011810),
(1, 1761400000000011811),
(1, 1761400000000011812),
(1, 1761400000000011813);

-- ========== 3. 财务（1761300000000000012）：全部导入操作权限 ==========
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000012, 1761400000000011810),
(1761300000000000012, 1761400000000011811),
(1761300000000000012, 1761400000000011812),
(1761300000000000012, 1761400000000011813);

-- ========== 4. 人事（1761300000000000013）：仅上传导入权限 ==========
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000013, 1761400000000011810);

COMMIT;
