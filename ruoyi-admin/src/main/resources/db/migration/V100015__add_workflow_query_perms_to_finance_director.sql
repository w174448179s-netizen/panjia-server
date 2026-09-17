-- ============================================================================
-- V100015 给财务/总监角色补工作流查询权限
-- 问题：财务在实收/结佣明细页点"审批"按钮时报无权限
-- 根因：useBizApproval → getInfo(businessId) 查流程实例，
--        该接口 @SaCheckPermission("workflow:instance:query")，
--        财务角色未分配此权限码 → 403 被静默 catch → 提示"不在审批范围内"
-- 修复：给财务、总监角色分配工作流实例查询 + 任务列表权限
--   - workflow:instance:query (menu_id=1761400000000011653) → getInfo 接口
--   - workflow:task:list      (menu_id=1761400000000011631) → pageByTaskWait 接口
-- ============================================================================

INSERT INTO sys_role_menu (role_id, menu_id) VALUES
-- 财务角色（1761300000000000012）
(1761300000000000012, 1761400000000011653),  -- workflow:instance:query
(1761300000000000012, 1761400000000011631),  -- workflow:task:list
-- 总监角色（1761300000000000010）
(1761300000000000010, 1761400000000011653),  -- workflow:instance:query
(1761300000000000010, 1761400000000011631)   -- workflow:task:list
ON CONFLICT (role_id, menu_id) DO NOTHING;
