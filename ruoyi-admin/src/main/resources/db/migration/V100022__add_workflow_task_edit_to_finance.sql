-- ============================================================================
-- V100022 财务角色补工作流任务办理权限
-- 问题：财务在结佣明细页「审批中」单据上看不到「审批」按钮（节点明明在财务）
-- 根因：按钮渲染条件 checkPermi(['workflow:task:edit'])，财务角色未分配该
--       权限码（V100015 只补了 workflow:instance:query / workflow:task:list）。
--       总监在 V100001 已有 workflow:task:edit，故仅财务受影响。
--       后端 POST /workflow/task/completeTask 无 @SaCheckPermission（按办理人
--       校验），补权限码后前端按钮渲染与整条办理链路即可打通。
-- 修复：给财务角色绑定「待办任务修改」按钮（menu_id=1761400000000011660）
-- ============================================================================

INSERT INTO sys_role_menu (role_id, menu_id)
VALUES (1761300000000000012, 1761400000000011660)  -- workflow:task:edit
ON CONFLICT (role_id, menu_id) DO NOTHING;
