-- ============================================================
-- 员工域 V5.2 菜单对齐：一个「员工管理」菜单
-- 段位：V110003（2026-09-11 由 V100017 重命名）
-- 依据：盘家智管_员工域详细设计_V5.2.md §0.5 / §8
-- 说明：
--   1) 删除旧「职级与社保模板」(2002)、「师徒关系」(2003) 菜单——
--      职级/师傅合并进员工弹窗的算薪配置与基本信息，不再有独立页面；
--   2) 旧「员工档案」(2001) 改名「员工管理」，组件路径不变（people/employee/index）；
--   3) 新增 F 按钮：新增/修改/导入/对账，挂在 2001 下；
--   4) 权限：人事(13)/总监(10) 全权（含 F 按钮）；财务(12) 只读（仅菜单，无 F 按钮）。
-- ============================================================

BEGIN;

-- ---------- 一、删除旧菜单及其角色绑定 ----------
DELETE FROM sys_role_menu WHERE menu_id IN (1761400000000002002, 1761400000000002003);
DELETE FROM sys_menu      WHERE menu_id IN (1761400000000002002, 1761400000000002003);

-- ---------- 二、员工档案 → 员工管理 ----------
UPDATE sys_menu
SET menu_name = '员工管理',
    perms     = 'people:employee:list',
    icon      = 'peoples',
    remark    = '员工管理菜单（基本信息+算薪配置一个弹窗，岗位多选、职级/社保/公积金/商保/宿舍/兼职/师傅事实维护）'
WHERE menu_id = 1761400000000002001;

-- ---------- 三、F 按钮权限（挂在员工管理 C 菜单下） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, remark)
VALUES (1761400000000002004, '员工新增', 1761400000000002001, 1, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:employee:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), '员工管理-新增按钮')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, remark)
VALUES (1761400000000002005, '员工修改', 1761400000000002001, 2, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:employee:edit', '#', '', '', 1761000000000000100, 1761100000000000001, now(), '员工管理-修改按钮（含离职）')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, remark)
VALUES (1761400000000002006, '员工导入', 1761400000000002001, 3, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:employee:import', '#', '', '', 1761000000000000100, 1761100000000000001, now(), '员工管理-导入按钮（走导入域 EMPLOYEE 向导）')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, remark)
VALUES (1761400000000002007, '员工对账', 1761400000000002001, 4, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:employee:reconcile', '#', '', '', 1761000000000000100, 1761100000000000001, now(), '员工管理-对账按钮（people 单向覆盖 sys_user 岗位/角色/部门/离职禁用）')
ON CONFLICT (menu_id) DO NOTHING;

-- ---------- 四、角色绑定（人事/总监全权；财务只读不绑 F） ----------
INSERT INTO sys_role_menu (role_id, menu_id)
VALUES
(1761300000000000013, 1761400000000002004),  -- 人事-员工新增
(1761300000000000013, 1761400000000002005),  -- 人事-员工修改
(1761300000000000013, 1761400000000002006),  -- 人事-员工导入
(1761300000000000013, 1761400000000002007),  -- 人事-员工对账
(1761300000000000010, 1761400000000002004),  -- 总监-员工新增
(1761300000000000010, 1761400000000002005),  -- 总监-员工修改
(1761300000000000010, 1761400000000002006),  -- 总监-员工导入
(1761300000000000010, 1761400000000002007)   -- 总监-员工对账
ON CONFLICT (role_id, menu_id) DO NOTHING;

COMMIT;
