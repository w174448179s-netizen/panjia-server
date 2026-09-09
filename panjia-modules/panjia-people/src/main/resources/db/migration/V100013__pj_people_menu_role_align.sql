-- ============================================================
-- Task-1-3: 员工模块（people 域）菜单 / 角色 / 权限对齐
-- 归属域：panjia-people
-- 背景：
--   1) 员工模块控制器（EmployeeController / EmployeeLevelController /
--      MentorRelationController）与前端页面已使用按钮级权限
--      people:employee:add/edit/resign、people:level:change、people:mentor:add，
--      但 V100001 菜单种子只有 C 菜单的 list 权限，缺 F 按钮 →
--      除超级管理员外所有角色调用接口 403、按钮被隐藏。
--   2) V100012 角色映射种子沿用了设计文档的占位 sys_role_id（100/110/120/130），
--      与 V100001 实际创建的角色 ID（1761300000000000010~14）不一致 →
--      角色联动（EmployeeRoleChangedEvent 消费者）会绑定到不存在的角色。
-- 对齐依据：
--   docs/盘家智管_薪酬与收支_菜单设计_生产上线版.md 第三章功能矩阵：
--     基础档案 → 总监=查看、算薪人员=查看、人事=编辑，店长/经纪人=不可见
-- 幂等性：
--   全部 INSERT 带 ON CONFLICT DO NOTHING、UPDATE 天然幂等，
--   手工预执行后 Flyway 再次执行无副作用。
-- 依赖：V100001（菜单种子）、V100012（角色映射表）
-- ============================================================

BEGIN;

-- ============================================================
-- 一、补充 F 按钮权限（挂在对应 C 菜单下，沿用 RuoYi 惯例）
-- menu_id 段：1761400000000002004~208（V100001 的 people 段内顺延）
-- ============================================================

-- 员工档案（1761400000000002001）按钮
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002004, '员工新增', 1761400000000002001, 1, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:employee:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '员工档案-新增员工按钮')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002005, '员工编辑', 1761400000000002001, 2, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:employee:edit', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '员工档案-编辑员工按钮')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002006, '员工离职', 1761400000000002001, 3, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:employee:resign', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '员工档案-员工离职按钮')
ON CONFLICT (menu_id) DO NOTHING;

-- 职级与社保模板（1761400000000002002）按钮
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002007, '职级变更', 1761400000000002002, 1, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:level:change', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '职级模板-职级变更按钮（晋升/降级）')
ON CONFLICT (menu_id) DO NOTHING;

-- 师徒关系（1761400000000002003）按钮
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002008, '师徒绑定', 1761400000000002003, 1, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:mentor:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '师徒关系-绑定按钮')
ON CONFLICT (menu_id) DO NOTHING;

-- ============================================================
-- 二、角色-菜单绑定（按功能矩阵：基础档案编辑 = 人事）
-- 总监(…10)/算薪人员(…12) 仅查看，C 菜单已在 V100001 绑定，无需绑按钮
-- ============================================================

INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000013, 1761400000000002004),  -- 人事-员工新增
(1761300000000000013, 1761400000000002005),  -- 人事-员工编辑
(1761300000000000013, 1761400000000002006),  -- 人事-员工离职
(1761300000000000013, 1761400000000002007),  -- 人事-职级变更
(1761300000000000013, 1761400000000002008)   -- 人事-师徒绑定
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- ============================================================
-- 三、修正 pj_people_role_mapping 占位 sys_role_id
-- V100012 沿用设计文档占位 ID（100/110/120/130），
-- 实际 sys_role（V100001 创建）：
--   总监   = 1761300000000000010
--   店长   = 1761300000000000011
--   算薪人员 = 1761300000000000012
--   人事   = 1761300000000000013
--   经纪人 = 1761300000000000014
-- ============================================================

UPDATE pj_people_role_mapping SET sys_role_id = 1761300000000000014 WHERE id = 12001;  -- AGENT → 经纪人
UPDATE pj_people_role_mapping SET sys_role_id = 1761300000000000011 WHERE id = 12002;  -- STORE_MANAGER → 店长
UPDATE pj_people_role_mapping SET sys_role_id = 1761300000000000012 WHERE id = 12003;  -- STORE_MANAGER → 算薪人员
UPDATE pj_people_role_mapping SET sys_role_id = 1761300000000000010 WHERE id = 12004;  -- DIRECTOR → 总监
UPDATE pj_people_role_mapping SET sys_role_id = 1761300000000000012 WHERE id = 12005;  -- DIRECTOR → 算薪人员

COMMIT;

-- ============================================================
-- 验证（人工核对用）：
--   1. SELECT perms, menu_name FROM sys_menu WHERE perms LIKE 'people:%' ORDER BY menu_id;
--      → 应有 8 条（3 个 C 菜单 list + 5 个 F 按钮）
--   2. SELECT r.role_name, m.perms FROM sys_role_menu rm
--      JOIN sys_role r ON r.role_id = rm.role_id
--      JOIN sys_menu m ON m.menu_id = rm.menu_id
--      WHERE m.perms LIKE 'people:%' ORDER BY r.role_id;
--      → 人事应有全部 8 条；总监/算薪人员各 3 条 list
--   3. SELECT employee_role, sys_role_id FROM pj_people_role_mapping;
--      → 5 条映射全部指向实际存在的角色 ID
-- ============================================================
