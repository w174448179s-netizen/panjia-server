-- ============================================================================
-- V100023 店长角色补流程撤销权限
-- 问题：店长在「我发起的」对审批中的单据点「撤销」报
--       「没有访问权限，请联系管理员授权」
-- 根因：撤销调 PUT /workflow/instance/cancelProcessApply，
--       @SaCheckPermission("workflow:instance:cancel")，
--       V100020 店长权限收窄时未包含该权限码。
-- 修复：给店长绑定「流程实例撤销」按钮（menu_id=1761400000000011659）。
--       撤销仅申请人本人可操作（工作流侧按发起人校验），业务侧监听
--       cancel 事件置 CANCELLED 并冲销明细，与结佣明细页「作废」殊途同归。
-- ============================================================================

INSERT INTO sys_role_menu (role_id, menu_id)
VALUES (1761300000000000011, 1761400000000011659)  -- workflow:instance:cancel
ON CONFLICT (role_id, menu_id) DO NOTHING;
