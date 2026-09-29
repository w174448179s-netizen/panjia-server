-- ============================================================
-- 薪酬域工作流定义 seed「最终态」：提成点调整审批（rate_adjust_approval，发布态）
-- 开始 → 提交申请(${initiator}) → 总监审核(role:1761300000000000010) → 结束；驳回回提交节点
-- 合并自 V160009（本 20 文件链中唯一的工作流定义语句；
-- V160004 仅为批次表接线 process_instance_id 列，归 V160001__pj_payroll_init.sql）
-- ============================================================

BEGIN;

-- 1. 流程定义（发布态）
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, version, is_publish,
                             form_custom, form_path, activity_status, create_time, create_by, del_flag, tenant_id)
VALUES (1762500000000000601, 'rate_adjust_approval', '提成点调整审批', 'CLASSICS', '1762300000000000200',
        '1', 1, 'N', '/payroll/rateadjust', 1, NOW(), '1761100000000000001', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

-- 2. 流程节点：开始 → 提交申请（申请人）→ 总监审核 → 结束
INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio,
                       coordinate, form_custom, version, create_time, create_by, ext, del_flag, tenant_id)
VALUES
    (1762500000000000602, 0, 1762500000000000601, 'ra_start', '开始', NULL, '0.000',
     '200,200|200,200', 'N', '1', NOW(), '1761100000000000001', '[]', '0', '000000'),
    (1762500000000000603, 1, 1762500000000000601, 'ra_apply', '提交申请', '${initiator}', '0.000',
     '360,200|360,200', 'N', '1', NOW(), '1761100000000000001',
     '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000'),
    (1762500000000000604, 1, 1762500000000000601, 'ra_review', '总监审核', 'role:1761300000000000010', '0.000',
     '540,200|540,200', 'N', '1', NOW(), '1761100000000000001',
     '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000'),
    (1762500000000000605, 2, 1762500000000000601, 'ra_end', '结束', NULL, '0.000',
     '900,200|900,200', 'N', '1', NOW(), '1761100000000000001', '[]', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

-- 3. 节点跳转：提交 → 总监审核；总监审核 通过→结束 / 驳回→回提交
INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type,
                       skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES
    (1762500000000000606, 1762500000000000601, 'ra_start',  0, 'ra_apply',  1, NULL, 'PASS', NULL, '280,200;320,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000607, 1762500000000000601, 'ra_apply',  1, 'ra_review', 1, NULL, 'PASS', NULL, '420,200;500,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000608, 1762500000000000601, 'ra_review', 1, 'ra_end',    2, '审核通过', 'PASS', NULL, '610,200;830,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000609, 1762500000000000601, 'ra_review', 1, 'ra_apply',  1, '驳回', 'REJECT', NULL, '560,320;400,320', NOW(), '1761100000000000001', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

COMMIT;
