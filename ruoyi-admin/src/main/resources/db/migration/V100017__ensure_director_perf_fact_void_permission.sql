-- V100017: 补齐总监角色「业绩作废/恢复」权限（幂等）
-- 背景：V100012 已定义权限码 perf:fact:void 并授权总监（1761300000000000010），
--       部分历史库可能缺失菜单或角色关联，此处做幂等补齐，可重复执行。
-- 超管（role_id=1）天然拥有全部权限，此处仍显式写入保持与 V100012 一致。

-- 1. 确保按钮权限菜单存在
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002613, '业绩作废/恢复', 1761400000000002610, 3, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:fact:void', '#', '', '', 176100000000000100, 1761100000000000001, now(), NULL, NULL, '总监作废/恢复业绩事实（不参与算薪/落入当月）')
ON CONFLICT (menu_id) DO NOTHING;

-- 2. 确保总监角色关联存在
INSERT INTO sys_role_menu (role_id, menu_id)
VALUES (1761300000000000010, 1761400000000002613)
ON CONFLICT (role_id, menu_id) DO NOTHING;
