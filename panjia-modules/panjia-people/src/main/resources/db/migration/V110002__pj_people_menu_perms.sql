-- ============================================================
-- pj_people 菜单按钮权限最终态（清库重建版）
-- 合并自：V110003, V110004（菜单备注修正部分）
-- 说明：
--   * 基础档案目录 1761400000000002000、「员工档案」1761400000000002001 及旧菜单
--     2002/2003 由 ruoyi-admin V100001__panjia_menu_seed.sql 播种，本文件在其之后执行；
--   * 旧「职级与社保模板」(2002)、「师徒关系」(2003) 菜单被本文件删除（最终态不存在）；
--   * 「员工档案」(2001) 改名「员工管理」，组件路径不变（people/employee/index）；
--   * 菜单 2006（员工导入）备注采用 V110004 的最终文本；
--   * V110013 曾删除考勤审批按钮 2014/2015，但其创建迁移已不在迁移链中，无需删除语句。
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
VALUES (1761400000000002006, '员工导入', 1761400000000002001, 3, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:employee:import', '#', '', '', 1761000000000000100, 1761100000000000001, now(), '员工管理-导入按钮（V6.0 回迁：people 域员工导入向导，两阶段诊断+单一大事务落地）')
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
