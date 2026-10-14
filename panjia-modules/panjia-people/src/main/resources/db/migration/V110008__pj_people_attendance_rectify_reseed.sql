-- ============================================================================
-- V110008 考勤修改审批流程种子重播：ID 段 0601~0609 → 0701~0709
-- 背景：
--   · V110007 误用 ID 段 1762500000000000601~0609，与 payroll 模块 V160003
--     rate_adjust_approval（提成点调整审批，先入库）撞段；
--     ON CONFLICT (id) DO NOTHING 使 attendance_rectify 种子被整体静默跳过，
--     发起流程报「流程【attendance_rectify】未发布」
--   · 清库重建场景更危险：V110007(110007) 先于 V160003(160003) 执行会抢占 0601 段，
--     反过来导致 rate_adjust_approval 种子丢失 —— 故本迁移先按 flow_code/node_code
--     前缀清理 0601 段的 rectify 残留（现网库删 0 行、重建库删 V110007 误插行），
--     保证两场景最终态一致：rectify@0701~0709 + rate_adjust@0601~0609
-- 段占用盘点（flow_definition）：0001 结佣申请 / 0401 考勤月度 / 0501 积分月度 /
--   0601 提成点调整 / 0701 本流程；新增流程播种前先全库查段
-- 幂等：删除按条件定点、插入 ON CONFLICT DO NOTHING，重复执行无副作用
-- ============================================================================

BEGIN;

-- ---------- 一、清理 0601~0609 段的 rectify 残留（仅 rect_ 前缀/本 flow_code，绝不动 rate_adjust） ----------
DELETE FROM flow_skip WHERE id BETWEEN 1762500000000000606 AND 1762500000000000609
    AND now_node_code LIKE 'rect\_%';
DELETE FROM flow_node WHERE id BETWEEN 1762500000000000602 AND 1762500000000000605
    AND node_code LIKE 'rect\_%';
DELETE FROM flow_definition WHERE id = 1762500000000000601 AND flow_code = 'attendance_rectify';

-- ---------- 二、考勤修改审批流程定义（发布态，0701 段） ----------
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, version, is_publish,
                             form_custom, form_path, activity_status, create_time, create_by, del_flag, tenant_id)
VALUES (1762500000000000701, 'attendance_rectify', '考勤修改审批', 'CLASSICS', '1762300000000000200',
        '1', 1, 'N', '/people/attendance/my', 1, NOW(), '1761100000000000001', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

-- 流程节点：开始 → 提交申请（经纪人）→ 人事审核（无超时自动通过）→ 结束
INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio,
                       coordinate, form_custom, version, create_time, create_by, ext, del_flag, tenant_id)
VALUES
    (1762500000000000702, 0, 1762500000000000701, 'rect_start', '开始', NULL, '0.000',
     '200,200|200,200', 'N', '1', NOW(), '1761100000000000001', '[]', '0', '000000'),
    (1762500000000000703, 1, 1762500000000000701, 'rect_submit', '提交申请',
     'role:1761300000000000014@@role:1761300000000000013@@role:1761300000000000010', '0.000',
     '360,200|360,200', 'N', '1', NOW(), '1761100000000000001',
     '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000'),
    (1762500000000000704, 1, 1762500000000000701, 'rect_hr', '人事审核', 'role:1761300000000000013', '0.000',
     '540,200|540,200', 'N', '1', NOW(), '1761100000000000001',
     '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000'),
    (1762500000000000705, 2, 1762500000000000701, 'rect_end', '结束', NULL, '0.000',
     '900,200|900,200', 'N', '1', NOW(), '1761100000000000001', '[]', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

-- 节点跳转：提交 → 人事审核；人事审核 通过→结束 / 驳回→回提交（回调置 REJECTED 并清理实例）
INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type,
                       skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES
    (1762500000000000706, 1762500000000000701, 'rect_start',  0, 'rect_submit', 1, NULL, 'PASS', NULL, '280,200;320,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000707, 1762500000000000701, 'rect_submit', 1, 'rect_hr',     1, NULL, 'PASS', NULL, '420,200;500,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000708, 1762500000000000701, 'rect_hr',     1, 'rect_end',    2, '审核通过', 'PASS', NULL, '610,200;830,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000709, 1762500000000000701, 'rect_hr',     1, 'rect_submit', 1, '驳回', 'REJECT', NULL, '560,320;400,320', NOW(), '1761100000000000001', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

COMMIT;
