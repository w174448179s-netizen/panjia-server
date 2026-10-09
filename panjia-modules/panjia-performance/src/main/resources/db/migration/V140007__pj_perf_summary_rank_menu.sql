-- ============================================================
-- 业绩汇总 + 业绩排行 菜单与权限（新签业绩 PERF_EXPECT 维度）
-- 父菜单：1761400000000002700「综合查询」
-- 数据权限（后端 PerformanceQueryServiceImpl.pageSummary/pageRank）：
--   经纪人→本人；总监→不限制；店长/财务→本部门子树；超管→不限制
-- ============================================================

BEGIN;

-- 业绩汇总（按月/季/年 + 员工聚合新签业绩报表）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002670, '业绩汇总', 1761400000000002700, 3, 'summary', 'performance/summary/index', NULL, 'N', 'Y', 'C', '0', '0', 'perf:summary:list', 'chart', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '业绩汇总报表（按月/季/年 + 员工聚合新签业绩）');

-- 业绩排行（按员工聚合新签业绩金额降序，分页）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002671, '业绩排行', 1761400000000002700, 4, 'rank', 'performance/rank/index', NULL, 'N', 'Y', 'C', '0', '0', 'perf:rank:list', 'star', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '业绩排行（按新签业绩金额降序，分页）');

-- 角色权限关联
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
-- 超级管理员
(1, 1761400000000002670),
(1, 1761400000000002671),
-- 租户管理员（与现有 2610/2640 等业绩菜单授权口径对齐）
(1761100000000000100, 1761400000000002670),
(1761100000000000100, 1761400000000002671),
-- 总监（全量数据，查所有人）
(1761300000000000010, 1761400000000002670),
(1761300000000000010, 1761400000000002671),
-- 店长（本店汇总报表）
(1761300000000000011, 1761400000000002670),
(1761300000000000011, 1761400000000002671),
-- 经纪人（本人汇总/排行）
(1761300000000000014, 1761400000000002670),
(1761300000000000014, 1761400000000002671)
ON CONFLICT (role_id, menu_id) DO NOTHING;

COMMIT;
