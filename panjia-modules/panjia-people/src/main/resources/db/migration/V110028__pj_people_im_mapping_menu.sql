-- ============================================================
-- V110028
-- IM 账号映射管理页（基础档案 → IM账号映射）
--
-- 背景：映射由渠道同步（V180003 钉钉人员映射）自动生成，需要可查、可人工补绑的管理界面。
-- 页面渠道无关，后续企微/飞书复用同一页（按 channel 筛选）。
-- 依赖：V110027（pj_people_im_mapping）、V100001（基础档案菜单 1761400000000002000）
-- ============================================================

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002023, 'IM账号映射', 1761400000000002000, 9, 'im-mapping', 'people/imMapping/index', NULL, 'N', 'Y', 'C', '0', '0', 'people:im:list', 'connection', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, 'IM 账号映射（钉钉/企微/飞书账号 ↔ 本地员工/登录账号，支持人工绑定）')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002024, '人工绑定', 1761400000000002023, 1, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'people:im:bind', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, 'IM账号映射-人工绑定/解绑按钮')
ON CONFLICT (menu_id) DO NOTHING;

-- 默认授权：超级管理员、总监、人事
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000001, 1761400000000002023),
(1761300000000000001, 1761400000000002024),
(1761300000000000010, 1761400000000002023),
(1761300000000000010, 1761400000000002024),
(1761300000000000013, 1761400000000002023),
(1761300000000000013, 1761400000000002024)
ON CONFLICT DO NOTHING;
