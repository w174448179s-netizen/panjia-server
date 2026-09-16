-- ============================================================
-- T-04 条件跳过财务：互斥网关 + flow_skip.skipCondition
-- 设计依据：审批集成设计说明 §2.3 / §七
-- ============================================================
-- 链路变更：
--   原 capp_director → capp_finance → capp_end（无条件进财务）
--   新 capp_director → [互斥] →(实收==应收)→ capp_end          （T-04 新增：无差异直跳结束）
--                  →(else)    → capp_finance → capp_end       （原 V150001 路径保留，作为默认分支）
--
-- skip_condition 使用 Warm-Flow 内置表达式 eq@@${realAmount}@@${expectedAmount}
-- 流程变量 realAmount / expectedAmount 由 CommissionApplicationService 在
--   1) 发起流程时通过 ApprovalStartCmd.variables 写入初值；
--   2) 总监办理前通过 ApprovalPort.setVariable 更新为最新值。
-- 业务层 afterDirectorPassed 不再调 completeAsSys 旁路完成财务节点，
-- 网关跳过时 capp_finance 节点不会创建，监听器不触发。
-- ============================================================

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762500000000000026, 1762500000000000001, 'capp_director', 1, 'capp_end', 2, '实收应收无差异直跳结束', 'PASS', 'eq@@${realAmount}@@${expectedAmount}', '560,120;880,200', now(), '1761100000000000001', '0', '000000');

COMMIT;
