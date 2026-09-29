-- ============================================================
-- pj_people 考勤/积分审批流 warm-flow 种子最终态（清库重建版）
-- 合并自：V110013, V110015（flow 种子部分）
-- 说明：
--   * 考勤月度审批 attendance_approval 占 flow 种子 ID 1762500000000000401~0409；
--   * 积分月度审批 score_approval 占 flow 种子 ID 1762500000000000501~0509；
--   * 两个业务单表的 process_instance_id/snapshot 列已折叠进 V110001 建表 DDL；
--   * 总监审核节点均配置 24 小时超时自动通过（节点 ext AutoApproval）；
--   * V110013 对菜单 2014/2015 的删除语句未保留（其创建迁移已不在迁移链中）。
-- ============================================================

BEGIN;

-- ---------- 一、考勤月度审批流程定义（发布态） ----------
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, version, is_publish,
                             form_custom, form_path, activity_status, create_time, create_by, del_flag, tenant_id)
VALUES (1762500000000000401, 'attendance_approval', '考勤月度审批', 'CLASSICS', '1762300000000000200',
        '1', 1, 'N', '/people/attendance', 1, NOW(), '1761100000000000001', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

-- 流程节点：开始 → 提交考勤（人事）→ 总监审核（24h 超时自动通过）→ 结束
INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio,
                       coordinate, form_custom, version, create_time, create_by, ext, del_flag, tenant_id)
VALUES
    (1762500000000000402, 0, 1762500000000000401, 'att_start', '开始', NULL, '0.000',
     '200,200|200,200', 'N', '1', NOW(), '1761100000000000001', '[]', '0', '000000'),
    (1762500000000000403, 1, 1762500000000000401, 'att_submit', '提交考勤', 'role:1761300000000000013@@role:1761300000000000010', '0.000',
     '360,200|360,200', 'N', '1', NOW(), '1761100000000000001',
     '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000'),
    (1762500000000000404, 1, 1762500000000000401, 'att_review', '总监审核', 'role:1761300000000000010', '0.000',
     '540,200|540,200', 'N', '1', NOW(), '1761100000000000001',
     '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"},{"code":"AutoApproval","value":"hours=24,skipType=PASS"}]',
     '0', '000000'),
    (1762500000000000405, 2, 1762500000000000401, 'att_end', '结束', NULL, '0.000',
     '900,200|900,200', 'N', '1', NOW(), '1761100000000000001', '[]', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

-- 节点跳转：提交 → 总监审核；总监审核 通过→结束 / 驳回→回提交
INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type,
                       skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES
    (1762500000000000406, 1762500000000000401, 'att_start',  0, 'att_submit', 1, NULL, 'PASS', NULL, '280,200;320,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000407, 1762500000000000401, 'att_submit', 1, 'att_review', 1, NULL, 'PASS', NULL, '420,200;500,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000408, 1762500000000000401, 'att_review', 1, 'att_end',    2, '审核通过', 'PASS', NULL, '610,200;830,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000409, 1762500000000000401, 'att_review', 1, 'att_submit', 1, '驳回', 'REJECT', NULL, '560,320;400,320', NOW(), '1761100000000000001', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

-- ---------- 二、积分月度审批流程定义（发布态） ----------
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, version, is_publish,
                             form_custom, form_path, activity_status, create_time, create_by, del_flag, tenant_id)
VALUES (1762500000000000501, 'score_approval', '积分月度审批', 'CLASSICS', '1762300000000000200',
        '1', 1, 'N', '/people/score', 1, NOW(), '1761100000000000001', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

-- 流程节点：开始 → 提交积分（人事）→ 总监审核（24h 超时自动通过）→ 结束
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

-- 节点跳转：提交 → 总监审核；总监审核 通过→结束 / 驳回→回提交
INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type,
                       skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES
    (1762500000000000506, 1762500000000000501, 'score_start',  0, 'score_submit', 1, NULL, 'PASS', NULL, '280,200;320,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000507, 1762500000000000501, 'score_submit', 1, 'score_review', 1, NULL, 'PASS', NULL, '420,200;500,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000508, 1762500000000000501, 'score_review', 1, 'score_end',    2, '审核通过', 'PASS', NULL, '610,200;830,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000509, 1762500000000000501, 'score_review', 1, 'score_submit', 1, '驳回', 'REJECT', NULL, '560,320;400,320', NOW(), '1761100000000000001', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

COMMIT;
