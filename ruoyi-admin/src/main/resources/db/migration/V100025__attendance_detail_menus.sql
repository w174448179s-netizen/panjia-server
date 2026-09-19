-- ============================================================
-- V100025: 考勤明细（员工域）菜单与角色授权
--
-- 1) 基础档案(2000) 下新增「考勤明细」C 菜单（仅人事/总监），
--    含 新增/修改/删除 3 个 F 按钮；
--    菜单 ID 使用 2010~2013（2004~2007 已被 V110003 员工管理按钮占用，
--    跨模块迁移不可复用，否则清库重跑时产生重复菜单/挤掉员工按钮）；
-- 2) 综合查询(2700) 下新增「考勤查询」C 菜单（全员，仅查本人，
--    后端 /people/attendance/my/** 按登录用户强制隔离）；
-- 3) 人事角色(013) 此前无综合查询目录授权，补授 2700；
-- 4) 全部 INSERT 幂等（ON CONFLICT DO NOTHING），不改动既有菜单。
--
-- 权限点：
--   people:attendance:list/add/edit/remove  管理端（hr/director）
--   people:attendance:my:query              本人查询（五个业务角色）
-- ============================================================

-- ---------- C 菜单：基础档案 → 考勤明细 ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002010, '考勤明细', 1761400000000002000, 4, 'attendance', 'people/attendance/index', NULL, 'N', 'Y', 'C', '0', '0', 'people:attendance:list', 'date', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '考勤明细菜单（人事/总监登记与维护员工日考勤）')
ON CONFLICT (menu_id) DO NOTHING;

-- ---------- F 按钮：考勤明细-新增/修改/删除 ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002011, '考勤新增', 1761400000000002010, 1, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:attendance:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '考勤明细-新增按钮')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002012, '考勤修改', 1761400000000002010, 2, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:attendance:edit', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '考勤明细-修改按钮')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002013, '考勤删除', 1761400000000002010, 3, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:attendance:remove', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '考勤明细-删除按钮')
ON CONFLICT (menu_id) DO NOTHING;

-- ---------- C 菜单：综合查询 → 考勤查询（本人） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002702, '考勤查询', 1761400000000002700, 3, 'my-attendance', 'people/attendance/my', NULL, 'N', 'Y', 'C', '0', '0', 'people:attendance:my:query', 'date', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '考勤查询（全员仅查本人日考勤记录）')
ON CONFLICT (menu_id) DO NOTHING;

-- ---------- 角色授权 ----------
-- 总监(010)、人事(013)：考勤明细管理菜单 + 3 个按钮
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000010, 1761400000000002010),
(1761300000000000010, 1761400000000002011),
(1761300000000000010, 1761400000000002012),
(1761300000000000010, 1761400000000002013),
(1761300000000000013, 1761400000000002010),
(1761300000000000013, 1761400000000002011),
(1761300000000000013, 1761400000000002012),
(1761300000000000013, 1761400000000002013)
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 五个业务角色：综合查询 → 考勤查询（本人）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000010, 1761400000000002702),
(1761300000000000011, 1761400000000002702),
(1761300000000000012, 1761400000000002702),
(1761300000000000013, 1761400000000002702),
(1761300000000000014, 1761400000000002702)
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 人事(013) 补授综合查询目录（此前仅 010/011/012/014 拥有 2700）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000013, 1761400000000002700)
ON CONFLICT (role_id, menu_id) DO NOTHING;
