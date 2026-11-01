-- ============================================================
-- V180003
-- 钉钉人员映射同步按钮权限（基础档案 → 钉钉同步 → 同步人员映射）
--
-- 背景：钉钉工作通知/待办推送依赖本地账号 ↔ 钉钉 userid 映射，
--       映射由「钉钉通讯录 → pj_people_im_mapping」同步生成。
-- 依赖：V180001（钉钉同步页面菜单 1761400000000002720）
-- ============================================================

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002724, '同步人员映射', 1761400000000002720, 2, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'dingtalk:sync:employee', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '手动同步钉钉通讯录到 IM 账号映射表')
ON CONFLICT (menu_id) DO NOTHING;

-- 默认授权：超级管理员、总监、人事
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000001, 1761400000000002724),
(1761300000000000010, 1761400000000002724),
(1761300000000000013, 1761400000000002724)
ON CONFLICT DO NOTHING;
