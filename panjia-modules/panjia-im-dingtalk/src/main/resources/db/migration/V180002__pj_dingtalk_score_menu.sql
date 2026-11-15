-- ============================================================
-- 钉钉集成域：新增「同步积分」按钮权限（V18 段位增量）
-- 归属域：panjia-im-dingtalk
-- 说明：V180001 已建「钉钉同步」页面（1761400000000002720），
--       本脚本补充积分同步按钮（权限 dingtalk:sync:score）。
-- ============================================================

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002724, '同步积分', 1761400000000002720, 2, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'dingtalk:sync:score', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '手动触发钉钉数字区县积分流水同步')
ON CONFLICT DO NOTHING;

-- 默认授权：超级管理员（1761300000000000001）、总监（1761300000000000010）、人事（1761300000000000013）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000001, 1761400000000002724),
(1761300000000000010, 1761400000000002724),
(1761300000000000013, 1761400000000002724)
ON CONFLICT DO NOTHING;
