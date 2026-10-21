-- ============================================================
-- V200008 工作流审批节点兜底：非总监单角色节点追加总监角色 claim 端点
-- 背景：
--   Warm-Flow 在流程节点激活时，把 permission_flag 中的角色一次性解析为 flow_user 快照。
--   如果当时系统里没人有这个角色（如 rect_hr 只有人事角色、当时无人事），
--   就永远不会有人能认领这单 → 流程挂死在途。
--   事后给某人补赋角色、或总监希望兜底审批，快照机制无法自动补授权。
-- 修复策略：
--   方案A（本脚本）：给 5 个「仅非总监单角色」审批节点追加总监角色 @@role:1761300000000000010
--     → 总监作为兜底审批人，流程挂死风险从单角色归零变为双角色兜底；
--     → 幂等 UPDATE：按 flow_node.node_code + permission_flag 未含总监才追加；
--     → 已运行中的在途单不受影响（快照已冻结），对后续新发起的单生效。
--   方案B（后端 Controller 补 claim 端点）：
--     任何事后补赋角色的人，可通过业务列表页的「审批」按钮触发 claim，
--     后端校验角色匹配后把自己追加到 flow_user，再正常办理。
-- 风险节点盘点（单角色审批节点，不含 ${initiator} 自办节点）：
--   1. attendance_rectify.rect_hr          人事 ONLY → 追加总监兜底
--   2. perf_received.rcv_finance           财务 ONLY → 追加总监兜底
--   3. commission_apply.capp_finance       财务 ONLY → 追加总监兜底
--   4. commission_adjust.adjust_verify     财务 ONLY → 追加总监兜底
--   5. payroll_supplement.supplement_verify 财务 ONLY → 追加总监兜底
--   其余单角色节点（如 perf_director/capp_director/ra_review 等总监独占节点）
--   无更高层级角色可兜底 → 只能靠方案B claim 端点补救（本次不做迁移改动）。
-- ============================================================

BEGIN;

-- 定义总监角色 ID 变量（PostgreSQL 用 CTE 方式固定）
-- 总监 role_key = director, role_id = 1761300000000000010
-- 业务字典已确认：总监角色 role_id 固定不变

-- ---------- 1. rect_hr（考勤修改-人事审核）：人事 → 追加总监兜底 ----------
UPDATE flow_node
SET permission_flag = permission_flag || '@@role:1761300000000000010',
    update_time     = NOW()
WHERE node_code = 'rect_hr'
  AND permission_flag = 'role:1761300000000000013';

-- ---------- 2. rcv_finance（实收业绩-财务审批）：财务 → 追加总监兜底 ----------
UPDATE flow_node
SET permission_flag = permission_flag || '@@role:1761300000000000010',
    update_time     = NOW()
WHERE node_code = 'rcv_finance'
  AND permission_flag = 'role:1761300000000000012';

-- ---------- 3. capp_finance（结佣申请-财务审批）：财务 → 追加总监兜底 ----------
UPDATE flow_node
SET permission_flag = permission_flag || '@@role:1761300000000000010',
    update_time     = NOW()
WHERE node_code = 'capp_finance'
  AND permission_flag = 'role:1761300000000000012';

-- ---------- 4. adjust_verify（结佣调整-核验财务）：财务 → 追加总监兜底 ----------
UPDATE flow_node
SET permission_flag = permission_flag || '@@role:1761300000000000010',
    update_time     = NOW()
WHERE node_code = 'adjust_verify'
  AND permission_flag = 'role:1761300000000000012';

-- ---------- 5. supplement_verify（补发单-核验财务）：财务 → 追加总监兜底 ----------
UPDATE flow_node
SET permission_flag = permission_flag || '@@role:1761300000000000010',
    update_time     = NOW()
WHERE node_code = 'supplement_verify'
  AND permission_flag = 'role:1761300000000000012';

COMMIT;
