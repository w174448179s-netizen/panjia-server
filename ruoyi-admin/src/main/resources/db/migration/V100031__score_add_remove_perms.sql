-- ============================================================
-- V100031: 积分管理 新增/删除 按钮权限
--
-- 「积分管理」菜单（1761400000000002020，V100026 创建，V100030 改名）下新增：
--   2021  积分新增  people:score:add      （手工补录积分记录）
--   2022  积分删除  people:score:remove   （删除积分记录）
-- 修改按钮（原始事实编辑）沿用 people:score:list 权限（V100029 前已实现）。
-- 授权总监(010)、人事(013)，与积分管理菜单一致。
-- ============================================================

-- ---------- F 按钮 ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761400000000002021, '积分新增', 1761400000000002020, 1, '', NULL, NULL, 'N', 'N', 'F', '0', '0', 'people:score:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '手工新增积分记录（补录/修正）'),
(1761400000000002022, '积分删除', 1761400000000002020, 2, '', NULL, NULL, 'N', 'N', 'F', '0', '0', 'people:score:remove', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '删除积分记录（期间锁定后不可删）')
ON CONFLICT (menu_id) DO NOTHING;

-- ---------- 角色授权：总监(010)、人事(013) ----------
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000010, 1761400000000002021),
(1761300000000000010, 1761400000000002022),
(1761300000000000013, 1761400000000002021),
(1761300000000000013, 1761400000000002022)
ON CONFLICT (role_id, menu_id) DO NOTHING;
