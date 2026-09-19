-- ============================================================
-- V100026: 绩效积分明细（员工域）菜单与角色授权
--
-- 基础档案(2000) 下新增「绩效积分」C 菜单（仅人事/总监）：
--   积分数据全部来自《二手积分日报5.0版》导入同步（无人工登记入口），
--   页面提供查询、提交审批、查看审批单；无新增/修改/删除按钮。
--   菜单 ID 使用 2020（考勤明细占 2010~2013，全仓查重后取新段位）。
--
-- 权限点：
--   people:score:list  积分明细查询（含提交审批入口的页面可见性控制）
-- ============================================================

-- ---------- C 菜单：基础档案 → 绩效积分 ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002020, '绩效积分', 1761400000000002000, 5, 'score', 'people/score/index', NULL, 'N', 'Y', 'C', '0', '0', 'people:score:list', 'star', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '绩效积分明细（人事/总监查询积分汇总、提交月度审批）')
ON CONFLICT (menu_id) DO NOTHING;

-- ---------- 角色授权：总监(010)、人事(013) ----------
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000010, 1761400000000002020),
(1761300000000000013, 1761400000000002020)
ON CONFLICT (role_id, menu_id) DO NOTHING;
