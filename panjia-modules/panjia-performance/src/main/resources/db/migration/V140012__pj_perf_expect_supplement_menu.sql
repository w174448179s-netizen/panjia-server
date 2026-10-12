-- ============================================================
-- 新签补录按钮权限：实收明细页 DRAFT（待补新签）状态操作列
-- 场景：有实收无新签的合同，手工补录 PERF_EXPECT 事实并触发 DRAFT 单重评
-- 父菜单：1761400000000002640「实收明细」
-- ============================================================

BEGIN;

-- 新签补录（按钮）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002646, '新签补录', 1761400000000002640, 6, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:fact:supplement', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '待补新签单据手工录入新签业绩（DRAFT 状态可见）');

-- 角色授权：超管 / 租户管理员 / 总监 / 店长 / 财务（经纪人不参与）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1, 1761400000000002646),
(1761100000000000100, 1761400000000002646),
(1761300000000000010, 1761400000000002646),
(1761300000000000011, 1761400000000002646),
(1761300000000000012, 1761400000000002646)
ON CONFLICT (role_id, menu_id) DO NOTHING;

COMMIT;
