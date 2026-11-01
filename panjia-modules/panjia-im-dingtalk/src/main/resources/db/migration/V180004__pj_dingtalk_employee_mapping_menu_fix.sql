-- ============================================================
-- V180004
-- 修复「同步钉钉账号」按钮权限未落库的问题
--
-- 根因：V180003 与 V180002 复用了同一个 menu_id（1761400000000002724，
--       V180002 已占用为「同步积分」/dingtalk:sync:score），
--       V180003 的 ON CONFLICT (menu_id) DO NOTHING 导致
--       dingtalk:sync:employee 这条按钮权限从未写入，
--       前端 checkPermi 判定失败 → 钉钉同步页签、钉钉账号页都看不到同步按钮。
-- 处理：为「同步钉钉账号」分配独立 menu_id 1761400000000002725 并授权。
-- 依赖：V180001（钉钉同步页面 1761400000000002720）、V180002（2724 同步积分）
-- ============================================================

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002725, '同步钉钉账号', 1761400000000002720, 3, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'dingtalk:sync:employee', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '手动同步钉钉通讯录到钉钉账号表')
ON CONFLICT (menu_id) DO NOTHING;

-- 默认授权：超级管理员（1761300000000000001）、总监（1761300000000000010）、人事（1761300000000000013）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000001, 1761400000000002725),
(1761300000000000010, 1761400000000002725),
(1761300000000000013, 1761400000000002725)
ON CONFLICT DO NOTHING;
