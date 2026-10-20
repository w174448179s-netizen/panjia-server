-- ============================================================================
-- V200005 综合查询 → 合同查询 菜单 + 权限授权
-- 背景：
--   综合查询目录（1761400000000002700）下新增「合同查询」（2703），员工仅能查自己的合同。
--   密码门与工资查询共用同一套密码（pj_people_employee.salary_query_password，
--   V110017 已建）。菜单组件 contract/my/index，权限 people:contract:my。
-- 授权：
--   总监(010)/店长(011)/财务(012)/人力(013)/经纪人(014) 均加（含综合查询目录本身）。
-- 幂等：ON CONFLICT DO NOTHING，重复执行无副作用。
-- ============================================================================

-- ---------- 菜单 ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (
    1761400000000002703, '合同查询', 1761400000000002700, 3,
    'my-contract', 'contract/my/index', NULL, 'N', 'Y', 'C', '0', '0',
    'people:contract:my', 'document', '', '',
    176100000000000100, 1761100000000000001, now(), NULL, NULL,
    '合同查询（本人视角：仅查自己的合同，密码门 + 1分钟自动锁定）'
)
ON CONFLICT (menu_id) DO NOTHING;

-- ---------- 权限按钮（作为子菜单 C 类型记录；本页面无按钮级权限，仅页面本身） ----------
-- 注：综合查询子菜单均为 C 类型（页面），无 F 类型按钮；前端控制仅依赖菜单可见 + 后端 @SaCheckPermission

-- ---------- 角色授权 ----------
-- 总监(1761300000000000010)
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000010, 1761400000000002700),
(1761300000000000010, 1761400000000002703)
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 店长(1761300000000000011)
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000011, 1761400000000002700),
(1761300000000000011, 1761400000000002703)
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 财务(1761300000000000012)
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000012, 1761400000000002700),
(1761300000000000012, 1761400000000002703)
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 人力(1761300000000000013)
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000013, 1761400000000002700),
(1761300000000000013, 1761400000000002703)
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 经纪人(1761300000000000014)
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000014, 1761400000000002700),
(1761300000000000014, 1761400000000002703)
ON CONFLICT (role_id, menu_id) DO NOTHING;
