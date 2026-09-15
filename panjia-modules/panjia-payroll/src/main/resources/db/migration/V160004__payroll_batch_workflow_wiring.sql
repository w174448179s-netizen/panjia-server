-- ============================================================================
-- V160004 算薪批次接入 warm-flow 工作流（payroll_batch）
--
-- 背景（业务直批下线）：
--   PayrollBatchService.approve/reject/lock 原先直接改批次状态，完全绕过
--   flow_definition 中已发布的 payroll_batch 流程（已发布未接线）。
--   本次将 审核通过 / 驳回 / 锁定 三个动作全部收敛为工作流节点办理：
--     payroll_submit（提交算薪，财务+店长）→ payroll_review（总监审核）
--     → payroll_lock（总监锁定）→ payroll_end
--   业务侧只保留 calculate / submit / pay 与查询端点。
--
-- 变更：
--   1. pj_payroll_batch 增加 process_instance_id（与 pj_perf_received_apply /
--      pj_commission_application 同款口径，存 Warm-Flow 实例 ID）
--   2. 下线三个业务审批按钮权限码及其角色授权（动作改由「我的待办」办理，
--      引擎按 flow_user 名单判权，不再需要 @SaCheckPermission 前置关卡）：
--      · 2307 锁定批次   payroll:batch:lock
--      · 2313 审批通过   payroll:batch:approve
--      · 2314 驳回       payroll:batch:reject
--
-- 注意：不做校验断言（2026-09-14 用户决策：迁移脚本不内嵌守卫块）。
-- ============================================================================

-- 1. 批次表增加流程实例列
ALTER TABLE pj_payroll_batch
    ADD COLUMN IF NOT EXISTS process_instance_id VARCHAR(64);
COMMENT ON COLUMN pj_payroll_batch.process_instance_id IS 'Warm-Flow 流程实例ID(payroll_batch)';

-- 2. 下线业务审批按钮（先删授权再删菜单）
DELETE FROM sys_role_menu WHERE menu_id IN (
    1761400000000002307,  -- 锁定批次 payroll:batch:lock
    1761400000000002313,  -- 审批通过 payroll:batch:approve
    1761400000000002314   -- 驳回       payroll:batch:reject
);
DELETE FROM sys_menu WHERE menu_id IN (
    1761400000000002307,
    1761400000000002313,
    1761400000000002314
);
