-- ============================================================
-- 实收调整（RECEIVED_AMOUNT）菜单：查看实收调整单记录
-- 父菜单：1761400000000002200「业绩管理」
-- 发起入口在「实收明细」页 APPROVED 实收单操作列（非经纪人），
-- 本菜单仅承载记录查看；列表/详情接口与新签调整共用 /perf/adjust/*，
-- 权限复用 perf:adjust:list / perf:adjust:query（同一调整域，避免权限码冗余）
-- ============================================================

BEGIN;

-- 实收调整（菜单）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002634, '实收调整', 1761400000000002200, 6, 'received-adjust', 'performance/received-adjust/index', NULL, 'N', 'Y', 'C', '0', '0', 'perf:adjust:list', 'money', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '实收调整单记录（调整对象为实收明细，不涉及新签业绩）');

-- 调整单查询（按钮）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002635, '调整单查询', 1761400000000002634, 1, '', NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:adjust:query', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '实收调整单详情查看');

-- 角色授权：超管 / 租户管理员 / 总监 / 店长 / 财务
-- （实收调整发起按钮对非经纪人可见，记录查看口径与发起口径保持一致；经纪人不可见）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
-- 超级管理员
(1, 1761400000000002634),
(1, 1761400000000002635),
-- 租户管理员（与现有业绩菜单授权口径对齐）
(1761100000000000100, 1761400000000002634),
(1761100000000000100, 1761400000000002635),
-- 总监
(1761300000000000010, 1761400000000002634),
(1761300000000000010, 1761400000000002635),
-- 店长
(1761300000000000011, 1761400000000002634),
(1761300000000000011, 1761400000000002635),
-- 财务
(1761300000000000012, 1761400000000002634),
(1761300000000000012, 1761400000000002635)
ON CONFLICT (role_id, menu_id) DO NOTHING;

COMMIT;
