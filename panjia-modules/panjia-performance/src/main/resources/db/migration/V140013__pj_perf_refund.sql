-- ============================================================================
-- 退单申请（pj_perf_refund）：与「手工冲正」分离的合同级全额退单
-- 背景：
--   现有逆向手段只有业绩冲正（MANUAL_OFFSET：只产生负业绩事实、不改单据状态），
--   合同层面缺少「退单」这一确定性业务动作与状态源，退单后也无法统计。
--   本文建独立退单表 + 独立审批流（flowCode=perf_refund，bizType=PERF_REFUND）。
-- 执行口径（审批通过后由 PerfRefundWorkflowListener 触发）：
--   1) 合同下全部 ACTIVE 正新签事实 → 逐条镜像生成 REDINK_REFUND 负事实，归属月=退单月；
--   2) 合同下全部 ACTIVE 实收明细 → 逐条镜像生成同额负实收明细，归属月=退单月；
--   3) 原事实/原明细一律不动，负流水由结佣与算薪引擎按月自然读取；
--   4) 已封账（已发薪）月份禁止退单；同合同生效中退单仅允许一笔；退单不可撤销（录错另发补正单）。
-- ID 段：退单表 无预置；流程 1762400000000001001~1024；菜单 1761400000000002660~2662。
-- ============================================================================

BEGIN;

-- ---------- 一、退单表 ----------
CREATE TABLE IF NOT EXISTS pj_perf_refund (
    id                  BIGINT                 PRIMARY KEY,
    refund_no           VARCHAR(32)            NOT NULL,
    contract_no         VARCHAR(100)           NOT NULL,
    order_no            VARCHAR(100),
    refund_period       VARCHAR(7)             NOT NULL,
    biz_type            VARCHAR(50),
    employee_id         BIGINT,
    dept_id             BIGINT,
    expect_amount       NUMERIC(18,2)          DEFAULT 0,
    real_amount         NUMERIC(18,2)          DEFAULT 0,
    reason              VARCHAR(500),
    payload_json        TEXT,
    status              VARCHAR(20)            NOT NULL DEFAULT 'SUBMITTED',
    process_instance_id VARCHAR(64),
    applicant_id        BIGINT                 NOT NULL,
    approver_id         BIGINT,
    approve_time        TIMESTAMP,
    operator_id         BIGINT,
    execute_time        TIMESTAMP,
    create_time         TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_perf_refund_no ON pj_perf_refund(refund_no);
CREATE INDEX IF NOT EXISTS idx_perf_refund_period   ON pj_perf_refund(refund_period, status);
CREATE INDEX IF NOT EXISTS idx_perf_refund_contract ON pj_perf_refund(contract_no);
CREATE INDEX IF NOT EXISTS idx_perf_refund_employee ON pj_perf_refund(employee_id, refund_period);
CREATE INDEX IF NOT EXISTS idx_perf_refund_dept     ON pj_perf_refund(dept_id);

COMMENT ON TABLE  pj_perf_refund IS '退单申请单（合同级全额冲退，退单状态源；区别于业绩冲正 MANUAL_OFFSET）';
COMMENT ON COLUMN pj_perf_refund.refund_no IS '退单号（TD 前缀）';
COMMENT ON COLUMN pj_perf_refund.refund_period IS '退单落账期间 YYYY-MM：负新签/负实收流水归属月';
COMMENT ON COLUMN pj_perf_refund.expect_amount IS '冲减新签合计（负数）';
COMMENT ON COLUMN pj_perf_refund.real_amount   IS '冲减实收合计（负数；无实收为 0）';
COMMENT ON COLUMN pj_perf_refund.status IS '状态：SUBMITTED/APPROVED/EXECUTED/REJECTED/CANCELLED';
COMMENT ON COLUMN pj_perf_refund.payload_json IS '退单明细快照 JSON（factId/归属月/员工/角色/占比/金额）';

-- ---------- 二、退单审批流程（perf_refund） ----------
-- 链路：开始 → 申请人(${initiator}) → 总监审批(role:1761300000000000010) → 结束
-- 审批通过后由 PerfRefundWorkflowListener 自动执行退单（负新签 + 负实收）。
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, "version", is_publish,
                            form_custom, form_path, activity_status, listener_type, listener_path, ext,
                            create_time, create_by, update_time, update_by, del_flag, tenant_id)
SELECT 1762400000000001001, 'perf_refund', '退单审批', 'CLASSICS', '1762300000000000200', '1', 1,
       'N', '/performance/refund', 1, NULL, NULL, NULL,
       NOW(), '1761100000000000001', NULL, NULL, '0', '000000'
WHERE NOT EXISTS (SELECT 1 FROM flow_definition WHERE flow_code = 'perf_refund');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio,
                      coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path,
                      "version", ext, del_flag, tenant_id, create_time, create_by)
SELECT 1762400000000001010, 0, 1762400000000001001, 'rfd_start', '开始', NULL, '0.000',
       '200,200|200,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', NOW(), '1761100000000000001'
WHERE NOT EXISTS (SELECT 1 FROM flow_node WHERE id = 1762400000000001010);

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio,
                      coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path,
                      "version", ext, del_flag, tenant_id, create_time, create_by)
SELECT 1762400000000001011, 1, 1762400000000001001, 'rfd_applicant', '申请人', '${initiator}', '0.000',
       '360,200|360,200', NULL, '', '', 'N', NULL, '1',
       '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', NOW(), '1761100000000000001'
WHERE NOT EXISTS (SELECT 1 FROM flow_node WHERE id = 1762400000000001011);

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio,
                      coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path,
                      "version", ext, del_flag, tenant_id, create_time, create_by)
SELECT 1762400000000001012, 1, 1762400000000001001, 'rfd_director', '总监审批', 'role:1761300000000000010', '0.000',
       '540,200|540,200', NULL, '', '', 'N', NULL, '1',
       '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', NOW(), '1761100000000000001'
WHERE NOT EXISTS (SELECT 1 FROM flow_node WHERE id = 1762400000000001012);

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio,
                      coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path,
                      "version", ext, del_flag, tenant_id, create_time, create_by)
SELECT 1762400000000001013, 2, 1762400000000001001, 'rfd_end', '结束', NULL, '0.000',
       '760,200|760,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', NOW(), '1761100000000000001'
WHERE NOT EXISTS (SELECT 1 FROM flow_node WHERE id = 1762400000000001013);

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type,
                       skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES
    (1762400000000001020, 1762400000000001001, 'rfd_start',     0, 'rfd_applicant', 1, NULL,   'PASS',   NULL, '220,200;310,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762400000000001021, 1762400000000001001, 'rfd_applicant', 1, 'rfd_director',  1, NULL,   'PASS',   NULL, '410,200;490,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762400000000001023, 1762400000000001001, 'rfd_director',  1, 'rfd_end',       2, '审批通过', 'PASS',   NULL, '590,200;700,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762400000000001024, 1762400000000001001, 'rfd_director',  1, 'rfd_applicant', 1, '驳回',   'REJECT', NULL, '540,320;360,320', NOW(), '1761100000000000001', '0', '000000')
ON CONFLICT (id) DO NOTHING;

-- ---------- 三、菜单与权限 ----------
-- 挂载在「业绩管理」(1761400000000002200) 下
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame,
                      is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept,
                      create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002660, '退单管理', 1761400000000002200, 8, 'refund', 'performance/refund/index', NULL,
        'N', 'Y', 'C', '0', '0', 'perf:refund:list', 'undo', '', '', 1761000000000000100,
        '1761100000000000001', NOW(), NULL, NULL, '退单管理（合同级全额冲退，审批通过生成负新签+负实收）')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame,
                      is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept,
                      create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002661, '退单查询', 1761400000000002660, 1, '', NULL, NULL,
        'N', 'Y', 'F', '0', '0', 'perf:refund:list', '#', '', '', 1761000000000000100,
        '1761100000000000001', NOW(), NULL, NULL, '')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame,
                      is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept,
                      create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002662, '发起退单', 1761400000000002660, 2, '', NULL, NULL,
        'N', 'Y', 'F', '0', '0', 'perf:refund:add', '#', '', '', 1761000000000000100,
        '1761100000000000001', NOW(), NULL, NULL, '')
ON CONFLICT (menu_id) DO NOTHING;

-- 角色授权：超管全量；总监/财务可发起；店长只读
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
    (1761300000000000001, 1761400000000002660),
    (1761300000000000001, 1761400000000002661),
    (1761300000000000001, 1761400000000002662),
    (1761300000000000010, 1761400000000002660),
    (1761300000000000010, 1761400000000002661),
    (1761300000000000010, 1761400000000002662),
    (1761300000000000012, 1761400000000002660),
    (1761300000000000012, 1761400000000002661),
    (1761300000000000012, 1761400000000002662),
    (1761300000000000011, 1761400000000002660),
    (1761300000000000011, 1761400000000002661)
ON CONFLICT (role_id, menu_id) DO NOTHING;

COMMIT;
