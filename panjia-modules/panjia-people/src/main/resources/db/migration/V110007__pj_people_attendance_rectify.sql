-- ============================================================
-- V110007 经纪人考勤修改申请 + 人事审批（attendance_rectify）
-- 背景：
--   · 考勤由人事导入/登记，经纪人仅可在「考勤查询」只读查看，异议只能线下处理
--   · 本迁移新增「考勤修改申请单」：经纪人按月发起月汇总指标调整（附截图+说明），
--     人事在「我的任务」审批，通过后回写 pj_people_attendance 并使月度审批失效回退，
--     人事再统一提交总监审批（attendance_approval 链路不变）
-- 说明：
--   · 修改粒度为月汇总指标（attendDays/restDays/lateCount/lateMinutes/missingCardCount
--     /absentDays/leaveDays/remark），每日明细 pj_people_attendance_detail 不动
--   · 部分唯一索引保证同一员工同一月份仅一条在途（SUBMITTED）申请
--   · 流程种子占 ID 段 1762500000000000601~0609（0401 考勤月度审批、0501 积分审批之后）
--   · 人事审核节点不配 24h 超时自动通过：避免错误修改自动写进考勤表
--   · 幂等：DDL 与种子均按存在性跳过，重复执行无副作用
-- ============================================================

BEGIN;

-- ---------- 一、考勤修改申请单 ----------
CREATE TABLE IF NOT EXISTS pj_people_attendance_rectify (
    id                  BIGINT       PRIMARY KEY,                 -- 雪花 ID
    employee_id         BIGINT       NOT NULL,                    -- 申请人员工 ID（pj_people_employee.employee_id）
    attend_month        DATE         NOT NULL,                    -- 考勤月份（当月 1 日）
    changes_json        TEXT         NOT NULL,                    -- 修改明细 [{field,fieldName,oldValue,newValue}]
    involve_dates       VARCHAR(500),                             -- 涉及日期（逗号分隔 yyyy-MM-dd，说明性）
    reason              VARCHAR(500) NOT NULL,                    -- 修改说明
    oss_ids             VARCHAR(1000) NOT NULL,                   -- 截图 ossId 列表（逗号分隔，≥1 张）
    status              VARCHAR(16)  NOT NULL DEFAULT 'SUBMITTED', -- 审批状态
    process_instance_id VARCHAR(64),                              -- Warm-Flow 流程实例 ID（attendance_rectify）
    submit_by           BIGINT,                                   -- 提交人（经纪人）用户ID
    submit_time         TIMESTAMP,                                -- 提交时间
    approve_by          BIGINT,                                   -- 审批人（人事）用户ID
    approve_time        TIMESTAMP,                                -- 审批时间
    reject_reason       VARCHAR(500),                             -- 驳回原因
    version             INT          NOT NULL DEFAULT 0,          -- 乐观锁
    create_time         TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time         TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  pj_people_attendance_rectify IS '考勤修改申请单：经纪人按月发起月汇总指标调整（附截图+说明），人事审批通过后回写月考勤';
COMMENT ON COLUMN pj_people_attendance_rectify.employee_id IS '申请人员工 ID，对应 pj_people_employee.employee_id';
COMMENT ON COLUMN pj_people_attendance_rectify.attend_month IS '考勤月份（当月 1 日）';
COMMENT ON COLUMN pj_people_attendance_rectify.changes_json IS '修改明细 JSON：[{field,fieldName,oldValue,newValue}]，field 限定月汇总指标白名单';
COMMENT ON COLUMN pj_people_attendance_rectify.involve_dates IS '涉及日期（逗号分隔 yyyy-MM-dd，说明性，可空）';
COMMENT ON COLUMN pj_people_attendance_rectify.reason IS '修改说明';
COMMENT ON COLUMN pj_people_attendance_rectify.oss_ids IS '截图 ossId 列表（逗号分隔，≥1 张）';
COMMENT ON COLUMN pj_people_attendance_rectify.status IS '审批状态：SUBMITTED-待人事审批 APPROVED-已通过 REJECTED-已驳回 CANCELED-已取消';
COMMENT ON COLUMN pj_people_attendance_rectify.process_instance_id IS 'Warm-Flow 流程实例ID(attendance_rectify)';
COMMENT ON COLUMN pj_people_attendance_rectify.submit_by IS '提交人（经纪人）用户ID';
COMMENT ON COLUMN pj_people_attendance_rectify.approve_by IS '审批人（人事）用户ID';
COMMENT ON COLUMN pj_people_attendance_rectify.reject_reason IS '驳回原因';
COMMENT ON COLUMN pj_people_attendance_rectify.version IS '乐观锁版本号';

-- 本人按月查询申请记录
CREATE INDEX IF NOT EXISTS idx_rectify_emp_month ON pj_people_attendance_rectify(employee_id, attend_month);
-- 同一员工同一月份仅一条在途申请（防并发重复提交；通过/驳回后可再发起）
CREATE UNIQUE INDEX IF NOT EXISTS uk_rectify_inflight ON pj_people_attendance_rectify(employee_id, attend_month)
    WHERE status = 'SUBMITTED';

-- ---------- 二、考勤修改审批流程定义（发布态） ----------
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, version, is_publish,
                             form_custom, form_path, activity_status, create_time, create_by, del_flag, tenant_id)
VALUES (1762500000000000601, 'attendance_rectify', '考勤修改审批', 'CLASSICS', '1762300000000000200',
        '1', 1, 'N', '/people/attendance/my', 1, NOW(), '1761100000000000001', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

-- 流程节点：开始 → 提交申请（经纪人）→ 人事审核（无超时自动通过）→ 结束
INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio,
                       coordinate, form_custom, version, create_time, create_by, ext, del_flag, tenant_id)
VALUES
    (1762500000000000602, 0, 1762500000000000601, 'rect_start', '开始', NULL, '0.000',
     '200,200|200,200', 'N', '1', NOW(), '1761100000000000001', '[]', '0', '000000'),
    (1762500000000000603, 1, 1762500000000000601, 'rect_submit', '提交申请',
     'role:1761300000000000014@@role:1761300000000000013@@role:1761300000000000010', '0.000',
     '360,200|360,200', 'N', '1', NOW(), '1761100000000000001',
     '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000'),
    (1762500000000000604, 1, 1762500000000000601, 'rect_hr', '人事审核', 'role:1761300000000000013', '0.000',
     '540,200|540,200', 'N', '1', NOW(), '1761100000000000001',
     '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000'),
    (1762500000000000605, 2, 1762500000000000601, 'rect_end', '结束', NULL, '0.000',
     '900,200|900,200', 'N', '1', NOW(), '1761100000000000001', '[]', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

-- 节点跳转：提交 → 人事审核；人事审核 通过→结束 / 驳回→回提交（回调置 REJECTED 并清理实例）
INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type,
                       skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES
    (1762500000000000606, 1762500000000000601, 'rect_start',  0, 'rect_submit', 1, NULL, 'PASS', NULL, '280,200;320,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000607, 1762500000000000601, 'rect_submit', 1, 'rect_hr',     1, NULL, 'PASS', NULL, '420,200;500,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000608, 1762500000000000601, 'rect_hr',     1, 'rect_end',    2, '审核通过', 'PASS', NULL, '610,200;830,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000609, 1762500000000000601, 'rect_hr',     1, 'rect_submit', 1, '驳回', 'REJECT', NULL, '560,320;400,320', NOW(), '1761100000000000001', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

COMMIT;
