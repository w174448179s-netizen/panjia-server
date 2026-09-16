-- V100014: 新增"完整业绩查询"菜单（业绩管理子菜单）
-- 以合同为维度展示完整业绩情况：新签/实收/调整/实收审批/结佣状态
-- 复用 perf:fact:list 权限码，所有拥有业绩管理权限的角色可见

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002650, '业绩查询', 1761400000000002200, 7, 'search', 'performance/search/index', NULL, 'N', 'Y', 'C', '0', '0', 'perf:fact:list', 'search', '', '', 176100000000000100, 1761100000000000001, now(), NULL, NULL, '完整业绩查询（合同维度：新签/实收/调整/实收审批/结佣状态）')
ON CONFLICT (menu_id) DO NOTHING;

-- 角色权限关联（与业绩管理其他子菜单一致）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1, 1761400000000002650),
(1761300000000000010, 1761400000000002650),
(1761300000000000011, 1761400000000002650),
(1761300000000000012, 1761400000000002650),
(1761300000000000014, 1761400000000002650)
ON CONFLICT (role_id, menu_id) DO NOTHING;
