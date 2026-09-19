-- ============================================================
-- V100026: 考勤审批按钮与角色授权
--
-- 「考勤明细」(2010) 下新增：
--   2014 提交审批  people:attendance:submit   → 人事(013)
--   2015 审批考勤  people:attendance:approve  → 总监(010)
-- 全部 INSERT 幂等（ON CONFLICT DO NOTHING）。
-- ============================================================

BEGIN;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark)
VALUES (1761400000000002014, '考勤提交审批', 1761400000000002010, 5, '', NULL, NULL, 1, 0, 'F', '0', '0', 'people:attendance:submit', '#', 103, 1, NOW(), '人事将当月考勤提交总监审批')
    ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark)
VALUES (1761400000000002015, '考勤审批', 1761400000000002010, 6, '', NULL, NULL, 1, 0, 'F', '0', '0', 'people:attendance:approve', '#', 103, 1, NOW(), '总监审批当月考勤（通过/驳回）')
    ON CONFLICT (menu_id) DO NOTHING;

-- 人事(013)：提交审批
INSERT INTO sys_role_menu (role_id, menu_id)
VALUES (1761300000000000013, 1761400000000002014)
    ON CONFLICT DO NOTHING;

-- 总监(010)：审批
INSERT INTO sys_role_menu (role_id, menu_id)
VALUES (1761300000000000010, 1761400000000002015)
    ON CONFLICT DO NOTHING;

COMMIT;
