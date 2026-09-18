-- V100024: 结佣流程财务驳回改为回到申请人节点（原回到总监节点）
-- 现状：capp_finance --REJECT--> capp_director，财务驳回后流程停在总监节点，
--       申请阶段无法重新编辑重提。
-- 目标：capp_finance --REJECT--> capp_applicant，财务驳回与总监驳回口径一致，
--       业务单置 REJECTED，申请人在「重提」时以系统身份办理申请人节点任务，
--       重新进入总监→财务审批。
-- 幂等：next_node_code = 'capp_director' 条件不满足时 UPDATE 0 行。

UPDATE flow_skip s
SET next_node_code = 'capp_applicant',
    update_time    = CURRENT_TIMESTAMP
FROM flow_definition d
WHERE s.definition_id = d.id
  AND d.flow_code = 'commission_apply'
  AND s.now_node_code = 'capp_finance'
  AND s.skip_type = 'REJECT'
  AND s.next_node_code = 'capp_director';
