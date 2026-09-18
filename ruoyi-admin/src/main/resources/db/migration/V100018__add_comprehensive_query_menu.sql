-- ============================================================
-- V100018: 新增「综合查询」一级菜单，收编业绩查询 + 工资查询（本人）
--
-- 1) 新增 M 目录：综合查询（order 35，介于业绩管理30与薪酬计算40之间）
-- 2) 新增 C 菜单：工资查询（本人视角，perms=payroll:my:query，四类业务角色均授权）
-- 3) 业绩查询菜单 2650 从「业绩管理」迁入「综合查询」（授权不变）
-- 4) 工资明细（组织视角，payroll:detail:list）收回为总监/财务专用：
--    删除店长(011)、经纪人(014) 对菜单 2302 的授权
--
-- 说明：超管走 selectMenuTreeAll 自动见全部菜单，无需 role_menu 绑定。
-- ============================================================

-- ---------- 一级目录：综合查询 ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002700, '综合查询', 0, 35, 'comprehensive', NULL, NULL, 'N', 'Y', 'M', '0', '0', NULL, 'search', '', '', 176100000000000100, 1761100000000000001, now(), NULL, NULL, '综合查询目录（业绩查询 + 工资查询）')
ON CONFLICT (menu_id) DO NOTHING;

-- ---------- 子菜单：工资查询（本人） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002701, '工资查询', 1761400000000002700, 2, 'my-salary', 'payroll/my/index', NULL, 'N', 'Y', 'C', '0', '0', 'payroll:my:query', 'money', '', '', 176100000000000100, 1761100000000000001, now(), NULL, NULL, '工资查询（本人视角：经纪人/店长/总监/财务仅查自己工资，可追溯结佣）')
ON CONFLICT (menu_id) DO NOTHING;

-- ---------- 业绩查询迁入综合查询（原挂业绩管理 2200，order=7） ----------
UPDATE sys_menu
SET parent_id = 1761400000000002700,
    order_num = 1
WHERE menu_id = 1761400000000002650;

-- ---------- 工资查询授权：总监/店长/财务/经纪人 ----------
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000010, 1761400000000002700),
(1761300000000000010, 1761400000000002701),
(1761300000000000011, 1761400000000002700),
(1761300000000000011, 1761400000000002701),
(1761300000000000012, 1761400000000002700),
(1761300000000000012, 1761400000000002701),
(1761300000000000014, 1761400000000002700),
(1761300000000000014, 1761400000000002701)
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- ---------- 工资明细（全员视角）收回为总监/财务专用 ----------
DELETE FROM sys_role_menu
WHERE menu_id = 1761400000000002302
  AND role_id IN (1761300000000000011, 1761300000000000014);
