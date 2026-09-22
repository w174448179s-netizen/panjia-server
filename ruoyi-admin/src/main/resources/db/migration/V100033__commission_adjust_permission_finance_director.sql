-- ============================================================================
-- V100033 结佣调整权限分配给财务与总监
-- 背景：结佣调整从「结佣明细」列表直接发起（合同级），后端创建接口
--       @SaCheckPermission("commission:adjust:add")，但该权限码此前未作为
--       菜单按钮落地，导致非超管无法发起调整。
-- 修复：在「结佣调整」菜单下新增「发起调整」按钮，并仅分配给
--       财务（role_key=finance, role_id=1761300000000000012）与
--       总监（role_key=director, role_id=1761300000000000010）。
--       店长/经纪人不具备调整权限。
-- ============================================================================

-- 1) 新增「发起调整」按钮（commission:adjust:add）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002204, '发起调整', 1761400000000002203, 1, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'commission:adjust:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '结佣调整-发起调整按钮（财务/总监）')
ON CONFLICT (menu_id) DO NOTHING;

-- 2) 分配给财务与总监（店长/经纪人不分配）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
-- 财务（1761300000000000012）
(1761300000000000012, 1761400000000002204),  -- commission:adjust:add
-- 总监（1761300000000000010）
(1761300000000000010, 1761400000000002204)   -- commission:adjust:add
ON CONFLICT (role_id, menu_id) DO NOTHING;
