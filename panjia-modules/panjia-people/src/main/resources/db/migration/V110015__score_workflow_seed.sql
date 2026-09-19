-- ============================================================================
-- V110015 积分月度审批接入 warm-flow 工作流（score_approval）+ 薪酬域积分扣款列下线
--
-- 业务：人事导入积分日报并核对后提交 → 总监在「我的待办」办理；
--       总监审核节点配置 24 小时超时自动通过（节点 ext AutoApproval）；
--       只有绩效等级 B/C（有提成扣点影响）的行需要总监审阅，
--       A 级（不扣点）免审；快照存业务表 snapshot，审批弹窗只展示扣点行。
--       流程办结回写 status（APPROVED/REJECTED），薪酬域 createBatch 卡点不变。
--
-- 结构对齐 V110013 考勤审批：pj_people_score_approval 加 process_instance_id /
--   snapshot 两列；流程结构 开始 → 提交积分（人事+总监）→ 总监审核（24h 自动过）→ 结束。
--
-- ID 段位：flow 种子 ID 统一用 1762500000000000xx 段，分配如下（全仓查重后）：
--   结佣 V150001 占 001 / 010~014 / 020~026；
--   payroll 业务表占 101~109 / 111~114 / 201 / 301~302；
--   考勤 V110013 占 401~409；
--   积分本迁移占 501~509。
-- ============================================================================

BEGIN;

-- 1. 业务单表增加流程实例列 + 扣点行快照
ALTER TABLE pj_people_score_approval
    ADD COLUMN IF NOT EXISTS process_instance_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS snapshot TEXT;

COMMENT ON COLUMN pj_people_score_approval.process_instance_id IS 'Warm-Flow 流程实例ID(score_approval)';
COMMENT ON COLUMN pj_people_score_approval.snapshot IS '提交时扣点行快照JSON（绩效等级 B/C 的行，A 级免审）';

-- 2. 流程定义（积分月度审批，发布态）
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, version, is_publish,
                             form_custom, form_path, activity_status, create_time, create_by, del_flag, tenant_id)
VALUES (1762500000000000501, 'score_approval', '积分月度审批', 'CLASSICS', '1762300000000000200',
        '1', 1, 'N', '/people/score', 1, NOW(), '1761100000000000001', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

-- 3. 流程节点：开始 → 提交积分（人事）→ 总监审核（24h 超时自动通过）→ 结束
INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio,
                       coordinate, form_custom, version, create_time, create_by, ext, del_flag, tenant_id)
VALUES
    (1762500000000000502, 0, 1762500000000000501, 'score_start', '开始', NULL, '0.000',
     '200,200|200,200', 'N', '1', NOW(), '1761100000000000001', '[]', '0', '000000'),
    (1762500000000000503, 1, 1762500000000000501, 'score_submit', '提交积分', 'role:1761300000000000013@@role:1761300000000000010', '0.000',
     '360,200|360,200', 'N', '1', NOW(), '1761100000000000001',
     '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000'),
    (1762500000000000504, 1, 1762500000000000501, 'score_review', '总监审核', 'role:1761300000000000010', '0.000',
     '540,200|540,200', 'N', '1', NOW(), '1761100000000000001',
     '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"},{"code":"AutoApproval","value":"hours=24,skipType=PASS"}]',
     '0', '000000'),
    (1762500000000000505, 2, 1762500000000000501, 'score_end', '结束', NULL, '0.000',
     '900,200|900,200', 'N', '1', NOW(), '1761100000000000001', '[]', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

-- 4. 节点跳转：提交 → 总监审核；总监审核 通过→结束 / 驳回→回提交
INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type,
                       skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES
    (1762500000000000506, 1762500000000000501, 'score_start',  0, 'score_submit', 1, NULL, 'PASS', NULL, '280,200;320,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000507, 1762500000000000501, 'score_submit', 1, 'score_review', 1, NULL, 'PASS', NULL, '420,200;500,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000508, 1762500000000000501, 'score_review', 1, 'score_end',    2, '审核通过', 'PASS', NULL, '610,200;830,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000509, 1762500000000000501, 'score_review', 1, 'score_submit', 1, '驳回', 'REJECT', NULL, '560,320;400,320', NOW(), '1761100000000000001', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

COMMIT;
