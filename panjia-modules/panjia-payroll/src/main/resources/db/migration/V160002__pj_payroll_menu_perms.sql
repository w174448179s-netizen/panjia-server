-- ============================================================
-- 薪酬域菜单与角色授权「最终态」
-- 保留：批次操作按钮 2311/2312 及授权；历史工资导入菜单 2323/2324
-- （按 V160018 迁入导入域后的最终值）及超管授权；下线锁定批次按钮 2307。
-- 合并自 V160003,V160004,V160016,V160017,V160018
-- 说明：V160003 的 2313/2314 与超管→2320/2321 错误授权均被后续版本删除，
--       最终态不再出现；V160016 对 2320/2321 的插入因与 V100028（提成点调整）冲突
--       实际未生效，同样不出现。
-- ============================================================

BEGIN;

-- ========== 1. 算薪批次操作按钮（V160003，挂「算薪批次」2301 下）==========
-- 2313 审批通过 / 2314 驳回 已被 V160004 下线，不再创建
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761400000000002311, '新建批次', 1761400000000002301, 4, '', NULL, NULL,
 'N', 'Y', 'F', '0', '0', 'payroll:batch:add', '#', '', '',
 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '新建算薪批次'),
(1761400000000002312, '提交审核', 1761400000000002301, 5, '', NULL, NULL,
 'N', 'Y', 'F', '0', '0', 'payroll:batch:submit', '#', '', '',
 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '提交批次进入总监审核')
ON CONFLICT (menu_id) DO NOTHING;

-- 2. 2311/2312 授权：总监(010) + 店长(011) + 财务(012)（V160003；审批/驳回改走工作流，不再授权）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000010, 1761400000000002311),  -- 总监 payroll:batch:add
(1761300000000000011, 1761400000000002311),  -- 店长 payroll:batch:add
(1761300000000000012, 1761400000000002311),  -- 财务 payroll:batch:add
(1761300000000000010, 1761400000000002312),  -- 总监 payroll:batch:submit
(1761300000000000011, 1761400000000002312),  -- 店长 payroll:batch:submit
(1761300000000000012, 1761400000000002312)   -- 财务 payroll:batch:submit
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 3. 下线「锁定批次」按钮 2307（V160004：锁定动作改由工作流节点办理；
--    2307 由 V100001__panjia_menu_seed.sql 创建，先删授权再删菜单）
DELETE FROM sys_role_menu WHERE menu_id = 1761400000000002307;
DELETE FROM sys_menu WHERE menu_id = 1761400000000002307;

-- ========== 4. 历史工资导入菜单（最终态 = V160017 建号 + V160018 迁入导入域）==========
-- C 型页面：挂在「数据导入」目录 1761400000000002100 下，权限码为导入域批次查询
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761400000000002323, '历史工资导入', 1761400000000002100, 5, 'payroll', 'import/payroll/index', NULL,
 'N', 'Y', 'C', '0', '0', 'import:batch:list', 'upload', '', '',
 1761000000000000100, 1761100000000000001, now(), 1, now(), '历史工资 Excel 导入（仅超级管理员）')
ON CONFLICT (menu_id) DO NOTHING;

-- F 型按钮：导入域撤销导入
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761400000000002324, '撤销导入', 1761400000000002323, 1, '', NULL, NULL,
 'N', 'Y', 'F', '0', '0', 'import:batch:revoke', '#', '', '',
 1761000000000000100, 1761100000000000001, now(), 1, now(), '上传 Excel 导入历史工资')
ON CONFLICT (menu_id) DO NOTHING;

-- 5. 仅授予超级管理员（V160017；含薪酬计算目录 2300 —— 建号时保证菜单树父级可见，V160018 未回收，按最终态保留）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000001, 1761400000000002300),  -- 超级管理员 → 薪酬计算目录
(1761300000000000001, 1761400000000002323),  -- 超级管理员 → 历史工资导入菜单
(1761300000000000001, 1761400000000002324)   -- 超级管理员 → 撤销导入按钮
ON CONFLICT (role_id, menu_id) DO NOTHING;

COMMIT;
