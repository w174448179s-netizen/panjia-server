-- =====================================================
-- 业绩域菜单与权限种子（V100021）
-- 挂在「数据管理」目录下（menu_id=1761400000000002530）
-- =====================================================

BEGIN;

-- 业绩管理一级菜单
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002600, '业绩管理', 1761400000000002530, 4, 'performance', NULL, NULL, 'N', 'Y', 'M', '0', '0', '', 'DataAnalysis', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '业绩域管理入口');

-- 业绩明细
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002610, '业绩明细', 1761400000000002600, 1, 'fact', 'performance/fact/index', NULL, 'N', 'Y', 'C', '0', '0', 'perf:fact:list', 'List', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '业绩事实明细列表');

-- 业绩明细按钮权限
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002611, '业绩查询', 1761400000000002610, 1, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:fact:query', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002612, '重新消费', 1761400000000002610, 2, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:fact:build', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

-- 调整单管理
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002620, '调整单管理', 1761400000000002600, 2, 'adjust', 'performance/adjust/index', NULL, 'N', 'Y', 'C', '0', '0', 'perf:adjust:list', 'Edit', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '业绩调整单管理');

-- 调整单按钮权限
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002621, '调整单查询', 1761400000000002620, 1, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:adjust:query', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002622, '新增调整单', 1761400000000002620, 2, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:adjust:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002623, '调整单审批', 1761400000000002620, 3, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:adjust:approve', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002624, '执行调整', 1761400000000002620, 4, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:adjust:execute', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002625, '取消调整', 1761400000000002620, 5, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:adjust:edit', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

-- 期间封账
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002630, '期间封账', 1761400000000002600, 3, 'period', 'performance/period/index', NULL, 'N', 'Y', 'C', '0', '0', 'perf:period:list', 'Calendar', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '业绩期间封账管理');

-- 期间封账按钮权限
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002631, '期间查询', 1761400000000002630, 1, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:period:query', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002632, '封账', 1761400000000002630, 2, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:period:close', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002633, '反结账', 1761400000000002630, 3, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:period:reopen', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

-- 角色权限关联：超级管理员 + 总监拥有全部业绩权限
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1, 1761400000000002600),
(1, 1761400000000002610),
(1, 1761400000000002611),
(1, 1761400000000002612),
(1, 1761400000000002620),
(1, 1761400000000002621),
(1, 1761400000000002622),
(1, 1761400000000002623),
(1, 1761400000000002624),
(1, 1761400000000002625),
(1, 1761400000000002630),
(1, 1761400000000002631),
(1, 1761400000000002632),
(1, 1761400000000002633),
(1761100000000000100, 1761400000000002600),
(1761100000000000100, 1761400000000002610),
(1761100000000000100, 1761400000000002611),
(1761100000000000100, 1761400000000002612),
(1761100000000000100, 1761400000000002620),
(1761100000000000100, 1761400000000002621),
(1761100000000000100, 1761400000000002622),
(1761100000000000100, 1761400000000002623),
(1761100000000000100, 1761400000000002624),
(1761100000000000100, 1761400000000002625),
(1761100000000000100, 1761400000000002630),
(1761100000000000100, 1761400000000002631),
(1761100000000000100, 1761400000000002632),
(1761100000000000100, 1761400000000002633);

COMMIT;
