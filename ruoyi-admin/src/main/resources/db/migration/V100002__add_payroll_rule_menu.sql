-- =====================================================================
-- V100002 补齐「薪酬规则配置」菜单
-- 背景：后端 RuleController + 前端 payroll/rule/index.vue 已就绪，但 DB 缺菜单入口
-- 菜单挂在「薪酬计算」目录下，order_num 排在「调整与补发」(5) 之后
-- =====================================================================

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002310, '薪酬规则配置', 1761400000000002300, 6, 'rule', 'payroll/rule/index', NULL, 'N', 'Y', 'C', '0', '0', 'payroll:rule:list', 'edit', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '薪酬规则配置菜单（职级/底薪/提点/社保/公积金/折算规则，只影响新算月份）');

INSERT INTO sys_role_menu (role_id, menu_id) VALUES
                                                 (1761300000000000010, 1761400000000002310)
    ON CONFLICT (role_id, menu_id) DO NOTHING;
