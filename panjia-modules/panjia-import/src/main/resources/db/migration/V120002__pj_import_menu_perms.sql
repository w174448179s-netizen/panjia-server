-- ============================================================
-- 盘家智管 · 数据导入域菜单与按钮权限最终态（清库重建版）
-- 模板管理菜单+按钮、导入操作按钮、撤销导入按钮、贝壳新签/实收导入菜单
-- 合并自 V120006, V120009, V120010, V120025（菜单部分）, V120026
-- 说明：
--   1) V120006 对旧菜单 1761400000000002105/2110/2111/2112 的 DELETE 已省略
--      （清库后无历史菜单可删；2110/2111/2112 被永久删除，不得再现；
--        2105 由 V120025 以新语义「贝壳实收导入」重建）；
--   2) V120026 的图标 UPDATE（icon='money'）已折叠进贝壳实收导入菜单 INSERT；
--   3) 父菜单 1761400000000002100/2101 由 ruoyi-admin V100001 创建，本文件只引用。
-- ============================================================

-- ========== 1. 模板管理菜单（V120006，挂在系统管理目录 1761400000000000001 下） ==========
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002510, '模板管理', 1761400000000000001, 80, 'template', 'import/template/index', NULL, 'N', 'Y', 'C', '0', '0', 'import:template:list', 'documentation', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '导入模板管理（单据导入+员工导入，可视化编辑/版本对比/复制激活）');

-- 单据导入模板按钮
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002511, '模板新增', 1761400000000002510, 1, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'import:template:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '新增/复制导入模板');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002512, '模板编辑', 1761400000000002510, 2, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'import:template:edit', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '编辑导入模板列映射');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002513, '模板激活', 1761400000000002510, 3, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'import:template:activate', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '激活/停用导入模板');

-- 员工导入模板按钮
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002517, '员工模板列表', 1761400000000002510, 3, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'people:template:list', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '查看员工导入模板列表');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002514, '员工模板新增', 1761400000000002510, 4, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'people:template:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '新增/复制员工导入模板');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002515, '员工模板编辑', 1761400000000002510, 5, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'people:template:edit', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '编辑员工导入模板列定义');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002516, '员工模板启用', 1761400000000002510, 6, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'people:template:activate', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '启用/停用员工导入模板');

-- 角色菜单关联：超级管理员（role_id=1）拥有全部权限
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1, 1761400000000002510),
(1, 1761400000000002511),
(1, 1761400000000002512),
(1, 1761400000000002513),
(1, 1761400000000002517),
(1, 1761400000000002514),
(1, 1761400000000002515),
(1, 1761400000000002516);

-- 总监/管理员角色（1761300000000000012）拥有全部模板管理权限
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000012, 1761400000000002510),
(1761300000000000012, 1761400000000002511),
(1761300000000000012, 1761400000000002512),
(1761300000000000012, 1761400000000002513),
(1761300000000000012, 1761400000000002517),
(1761300000000000012, 1761400000000002514),
(1761300000000000012, 1761400000000002515),
(1761300000000000012, 1761400000000002516);

-- ========== 2. 数据导入操作按钮（V120009，挂在「数据导入」1761400000000002100 下） ==========

-- 上传导入（贝壳业绩/考勤/积分共用同一权限）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000011810, '上传导入', 1761400000000002100, 10, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'import:batch:upload', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '上传导入文件+下载模板');

-- 重新归一化
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000011811, '重新归一化', 1761400000000002100, 11, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'import:batch:renormalize', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '基于原始文件重新解析与归一化');

-- 归档批次
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000011812, '归档批次', 1761400000000002100, 12, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'import:batch:archive', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '归档导入批次');

-- 忽略问题
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000011813, '忽略问题', 1761400000000002100, 13, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'import:issue:ignore', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '忽略导入问题行');

-- 超级管理员（role_id=1）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1, 1761400000000011810),
(1, 1761400000000011811),
(1, 1761400000000011812),
(1, 1761400000000011813);

-- 财务（1761300000000000012）：全部导入操作权限
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000012, 1761400000000011810),
(1761300000000000012, 1761400000000011811),
(1761300000000000012, 1761400000000011812),
(1761300000000000012, 1761400000000011813);

-- 人事（1761300000000000013）：仅上传导入权限
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000013, 1761400000000011810);

-- ========== 3. 撤销导入按钮（V120010） ==========

-- 撤销导入（硬删批次及下游数据，原始导入文件保留）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000011814, '撤销导入', 1761400000000002100, 14, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'import:batch:revoke', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '撤销已归档的导入批次，硬删下游数据');

-- 超级管理员
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1, 1761400000000011814);

-- 财务角色
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000012, 1761400000000011814);

-- ========== 4. 贝壳新签/实收导入菜单（V120025；V120026 图标 money 已折叠） ==========

-- KE_SIGNED 页面改名「贝壳新签导入」（菜单本体由 ruoyi-admin V100001 创建）
UPDATE sys_menu
SET menu_name = '贝壳新签导入',
    remark = '贝壳新签导入菜单（原「贝壳业绩导入」，实收改由贝壳实收导入产生）'
WHERE menu_id = 1761400000000002101;

-- 新增「贝壳实收导入」复用 shell 组件（V120026：图标调整为 money，到账金额语义）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002105, '贝壳实收导入', 1761400000000002100, 2, 'received', 'import/shell/index', NULL, 'N', 'Y', 'C', '0', '0', 'import:shell:list', 'money', '', '', 1761000000000000100, 1761100000000000001, now(), 1761100000000000001, now(), '贝壳实收导入菜单（理房通到账明细，按到账vs新签分流自动/人工建单）');

-- 与贝壳新签导入同权限的角色同步可见贝壳实收导入
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT rm.role_id, 1761400000000002105
FROM sys_role_menu rm
WHERE rm.menu_id = 1761400000000002101
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu t WHERE t.role_id = rm.role_id AND t.menu_id = 1761400000000002105);
