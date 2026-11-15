-- ============================================================
-- 实收审批恢复按钮权限：已作废单据恢复
-- 场景：CANCELLED 单据重新绑定未占用的实收明细，复用原单号重新发起审批
-- 父菜单：1761400000000002640「实收明细」
-- ============================================================

BEGIN;

-- 恢复实收单（按钮）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002647, '恢复实收单', 1761400000000002640, 7, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:received:restore', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '恢复已作废的实收单（复用原单号重新绑定明细并重新发起审批）');

-- 角色授权：超管 / 租户管理员 / 总监 / 店长 / 财务（经纪人不参与）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1, 1761400000000002647),
(1761100000000000100, 1761400000000002647),
(1761300000000000010, 1761400000000002647),
(1761300000000000011, 1761400000000002647),
(1761300000000000012, 1761400000000002647)
ON CONFLICT (role_id, menu_id) DO NOTHING;

COMMIT;
