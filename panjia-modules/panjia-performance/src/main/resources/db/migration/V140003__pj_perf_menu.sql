-- =====================================================
-- 业绩域菜单与权限种子（V140003）
-- 段位：V140003（2026-09-11 由 V100021 重命名）
--
-- 整改说明（2026-09-11 段位重整）：
--   原 V100021 引用 menu_id=1761400000000002530（"数据管理"顶级目录），
--   但 2530 在所有 SQL 中从未被 INSERT，属于孤儿引用。本次整改在文件顶部
--   自包含 INSERT menu_id=2530「数据管理」作为前置依赖，修复孤儿引用。
--
-- 设计意图：
--   * menu_id=2530「数据管理」顶级目录 — 本脚本自包含
--   * menu_id=2600「业绩管理（运维侧）」 — 挂在 2530 下
--   * 与 V100001 创建的 menu_id=2200「业绩管理」（业务侧）并存：
--       - 2200 走业务角色权限（总监/店长/财务/经纪人/人事）
--       - 2600 走超管运维权限（role_id=1）
--     两条菜单树对应不同的权限视图，运维侧与业务侧独立管理。
-- =====================================================

BEGIN;

-- 前置依赖：数据管理顶级目录（修复孤儿引用）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002530, '数据管理', 0, 80, 'data', NULL, NULL, 'N', 'Y', 'M', '0', '0', '', 'DataBoard', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '数据管理顶级目录（performance 域运维侧，由 V140003 自包含）');

-- 业绩管理一级菜单（运维侧，挂在数据管理目录下）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002600, '业绩管理', 1761400000000002530, 4, 'performance', NULL, NULL, 'N', 'Y', 'M', '0', '0', '', 'DataAnalysis', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '业绩域管理入口（运维侧）');

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
