-- ============================================================================
-- V100004 备份恢复菜单：图标修正 + 按钮权限
-- 背景：
--   · V100001 备份恢复菜单 icon=database，前端图标集（assets/icons/svg）无此图标，
--     菜单不显示图标 → 修正为 zip（图标集存在）
--   · 补齐页面操作按钮权限（立即备份/还原/下载/删除）
-- 幂等：菜单先删后插、授权防重。
-- ============================================================================

-- ---------- 图标修正 ----------
UPDATE sys_menu SET icon = 'zip'
WHERE menu_id = 1761400000000002501 AND icon = 'database';

-- ---------- 操作按钮（先删后插） ----------
DELETE FROM sys_role_menu WHERE menu_id IN (
    1761400000000002502, 1761400000000002503,
    1761400000000002504, 1761400000000002505);
DELETE FROM sys_menu WHERE menu_id IN (
    1761400000000002502, 1761400000000002503,
    1761400000000002504, 1761400000000002505);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761400000000002502, '备份执行', 1761400000000002501, 1, '', NULL, NULL, 'N', 'Y', 'F', '0', '0', 'system:backup:create',  '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '立即备份按钮'),
(1761400000000002503, '数据库还原', 1761400000000002501, 2, '', NULL, NULL, 'N', 'Y', 'F', '0', '0', 'system:backup:restore', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '还原数据库按钮（整库覆盖，危险操作）'),
(1761400000000002504, '备份下载', 1761400000000002501, 3, '', NULL, NULL, 'N', 'Y', 'F', '0', '0', 'system:backup:download', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '下载备份文件按钮'),
(1761400000000002505, '备份删除', 1761400000000002501, 4, '', NULL, NULL, 'N', 'Y', 'F', '0', '0', 'system:backup:delete',  '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '删除备份文件按钮');

-- ---------- 授权（跟随 V100002：管理角色已有备份恢复菜单 2501） ----------
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1761300000000000010, m.menu_id
FROM sys_menu m
WHERE m.menu_id IN (
    1761400000000002502, 1761400000000002503,
    1761400000000002504, 1761400000000002505)
  AND EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1761300000000000010 AND rm.menu_id = 1761400000000002501)
ON CONFLICT DO NOTHING;
