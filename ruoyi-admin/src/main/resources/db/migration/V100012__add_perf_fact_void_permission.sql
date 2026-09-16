-- V100012: 新增"业绩作废/恢复"权限码 + 角色分配
-- 仅总监角色拥有作废/恢复权限（超管天然拥有）
-- 作废：ACTIVE → VOIDED，不参与当月算薪
-- 恢复：VOIDED → ACTIVE，period 改为当前月

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002613, '业绩作废/恢复', 1761400000000002610, 3, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:fact:void', '#', '', '', 176100000000000100, 1761100000000000001, now(), NULL, NULL, '总监作废/恢复业绩事实（不参与算薪/落入当月）')
ON CONFLICT (menu_id) DO NOTHING;

-- 角色权限关联
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1, 1761400000000002613),
(1761300000000000010, 1761400000000002613)
ON CONFLICT (role_id, menu_id) DO NOTHING;
