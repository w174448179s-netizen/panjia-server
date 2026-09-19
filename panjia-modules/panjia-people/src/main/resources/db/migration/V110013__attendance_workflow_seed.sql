-- ============================================================================
-- V110013 考勤月度审批接入 warm-flow 工作流（attendance_approval）
--
-- 业务：人事导入考勤并核对后提交 → 总监在「我的待办」办理；
--       总监审核节点配置 24 小时超时自动通过（节点 ext AutoApproval）；
--       只有异常考勤（迟到/迟到分/缺卡/旷工/请假 > 0）需要总监审阅，
--       快照存业务表 snapshot，审批弹窗只展示异常行。
--       流程办结回写 status（APPROVED/REJECTED），薪酬域 createBatch 卡点不变。
--
-- 复用说明：pj_people_attendance_approval 原为自建审批状态机表（V110012），
--   本迁移将其升级为工作流业务单（加 process_instance_id / snapshot 两列），
--   审批动作全部收敛到「我的待办」，不再有业务接口直改状态。
--
-- ID 段位：flow 种子 ID 统一用 1762500000000000xx 段，分配如下（全仓查重后）：
--   结佣 V150001 占 001 / 010~014 / 020~026；
--   payroll 业务表占 101~109 / 111~114 / 201 / 301~302；
--   考勤本迁移占 401~409。
-- ============================================================================

BEGIN;

-- 1. 业务单表增加流程实例列 + 异常考勤快照
ALTER TABLE pj_people_attendance_approval
    ADD COLUMN IF NOT EXISTS process_instance_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS snapshot TEXT;

COMMENT ON COLUMN pj_people_attendance_approval.process_instance_id IS 'Warm-Flow 流程实例ID(attendance_approval)';
COMMENT ON COLUMN pj_people_attendance_approval.snapshot IS '提交时异常考勤快照JSON（迟到/迟到分/缺卡/旷工/请假 >0 的行）';

-- 2. 流程定义（考勤月度审批，发布态）
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, version, is_publish,
                             form_custom, form_path, activity_status, create_time, create_by, del_flag, tenant_id)
VALUES (1762500000000000401, 'attendance_approval', '考勤月度审批', 'CLASSICS', '1762300000000000200',
        '1', 1, 'N', '/people/attendance', 1, NOW(), '1761100000000000001', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

-- 3. 流程节点：开始 → 提交考勤（人事）→ 总监审核（24h 超时自动通过）→ 结束
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

-- 4. 节点跳转：提交 → 总监审核；总监审核 通过→结束 / 驳回→回提交
INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type,
                       skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES
    (1762500000000000406, 1762500000000000401, 'att_start',  0, 'att_submit', 1, NULL, 'PASS', NULL, '280,200;320,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000407, 1762500000000000401, 'att_submit', 1, 'att_review', 1, NULL, 'PASS', NULL, '420,200;500,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000408, 1762500000000000401, 'att_review', 1, 'att_end',    2, '审核通过', 'PASS', NULL, '610,200;830,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000409, 1762500000000000401, 'att_review', 1, 'att_submit', 1, '驳回', 'REJECT', NULL, '560,320;400,320', NOW(), '1761100000000000001', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

-- 5. 下线自建审批按钮（审批动作全部收敛到「我的待办」，引擎按 flow_user 判权）
DELETE FROM sys_role_menu WHERE menu_id IN (
    1761400000000002014,  -- 考勤提交审批 people:attendance:submit
    1761400000000002015   -- 考勤审批   people:attendance:approve
);
DELETE FROM sys_menu WHERE menu_id IN (
    1761400000000002014,
    1761400000000002015
);

COMMIT;
