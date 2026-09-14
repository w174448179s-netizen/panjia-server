-- ============================================================================
-- V100005 业务角色权限全面补全
-- 梳理所有 @SaCheckPermission 权限，补齐各角色缺失的业务操作权限：
--   总监：结佣审批、业绩调整审批、期间查看、数据导入
--   人事：全部数据导入、员工模板、工作流任务
--   店长：结佣详情查看、工作流任务
--   经纪人：工作流任务、结佣详情查看
-- ============================================================================

BEGIN;

-- ========== 总监（1761300000000000010）==========
-- 结佣：查看详情 + 审批 + 佣金明细
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000010, 1761400000000011820),  -- commission:apply:query
(1761300000000000010, 1761400000000011824),  -- commission:apply:approve
(1761300000000000010, 1761400000000011826),  -- commission:adjust:query
(1761300000000000010, 1761400000000011829),  -- commission:item:list
(1761300000000000010, 1761400000000011830),  -- commission:newsign:list
(1761300000000000010, 1761400000000011831),  -- commission:trace:query
(1761300000000000010, 1761400000000011832)   -- commission:consumelog:list
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 业绩调整（§4.4 总监可发起、§4.5 总监审批）：页面 + 发起/取消 + 审批/执行
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000010, 1761400000000002620),  -- perf:adjust:list
(1761300000000000010, 1761400000000002621),  -- perf:adjust:query
(1761300000000000010, 1761400000000002622),  -- perf:adjust:add
(1761300000000000010, 1761400000000002623),  -- perf:adjust:approve
(1761300000000000010, 1761400000000002624),  -- perf:adjust:execute
(1761300000000000010, 1761400000000002625)   -- perf:adjust:edit
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 期间封账：查看
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000010, 1761400000000002630),  -- perf:period:list
(1761300000000000010, 1761400000000002631)   -- perf:period:query
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 数据导入：全部页面 + 操作
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000010, 1761400000000002101),  -- import:shell:list
(1761300000000000010, 1761400000000002102),  -- import:attendance:list
(1761300000000000010, 1761400000000002103),  -- import:score:list
(1761300000000000010, 1761400000000002104),  -- import:batch:list
(1761300000000000010, 1761400000000011810),  -- import:batch:upload
(1761300000000000010, 1761400000000011811),  -- import:batch:renormalize
(1761300000000000010, 1761400000000011812),  -- import:batch:archive
(1761300000000000010, 1761400000000011813)   -- import:issue:ignore
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 业绩明细：重新消费
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000010, 1761400000000002612)   -- perf:fact:build
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- ========== 人事（1761300000000000013）==========
-- 数据导入：全部页面 + 操作
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000013, 1761400000000002101),  -- import:shell:list
(1761300000000000013, 1761400000000002104),  -- import:batch:list
(1761300000000000013, 1761400000000011811),  -- import:batch:renormalize
(1761300000000000013, 1761400000000011812),  -- import:batch:archive
(1761300000000000013, 1761400000000011813)   -- import:issue:ignore
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 员工导入模板
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000013, 1761400000000002517),  -- people:template:list
(1761300000000000013, 1761400000000002514),  -- people:template:add
(1761300000000000013, 1761400000000002515),  -- people:template:edit
(1761300000000000013, 1761400000000002516)   -- people:template:activate
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 工作流任务：我的待办 + 我发起的
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000013, 1761400000000011619),  -- 我的待办
(1761300000000000013, 1761400000000011629)   -- workflow:instance:currentList
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 业绩明细：查看（人事可查看业绩数据）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000013, 1761400000000002610),  -- perf:fact:list
(1761300000000000013, 1761400000000002611)   -- perf:fact:query
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- ========== 店长（1761300000000000011）==========
-- 结佣：查看详情 + 佣金明细
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000011, 1761400000000011820),  -- commission:apply:query
(1761300000000000011, 1761400000000011826),  -- commission:adjust:query
(1761300000000000011, 1761400000000011829),  -- commission:item:list
(1761300000000000011, 1761400000000011830),  -- commission:newsign:list
(1761300000000000011, 1761400000000011831),  -- commission:trace:query
(1761300000000000011, 1761400000000011832)   -- commission:consumelog:list
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 业绩明细：重新消费
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000011, 1761400000000002612)   -- perf:fact:build
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 业绩调整（§4.4 店长/店助可发起）：页面 + 查询 + 发起 + 取消（无审批权）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000011, 1761400000000002620),  -- perf:adjust:list
(1761300000000000011, 1761400000000002621),  -- perf:adjust:query
(1761300000000000011, 1761400000000002622),  -- perf:adjust:add
(1761300000000000011, 1761400000000002625)   -- perf:adjust:edit
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 工作流任务：我的待办 + 我发起的
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000011, 1761400000000011619),  -- 我的待办
(1761300000000000011, 1761400000000011629)   -- 我发起的
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- ========== 经纪人（1761300000000000014）==========
-- 结佣：查看详情
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000014, 1761400000000011820),  -- commission:apply:query
(1761300000000000014, 1761400000000011829)   -- commission:item:list
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 工作流任务：仅"我发起的"（经纪人无审批待办）
-- 移除经纪人的 我的待办/我的已办/我的抄送
DELETE FROM sys_role_menu WHERE role_id = 1761300000000000014
  AND menu_id IN (1761400000000011619, 1761400000000011632, 1761400000000011633);
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000014, 1761400000000011629)   -- 我发起的
ON CONFLICT (role_id, menu_id) DO NOTHING;

COMMIT;
