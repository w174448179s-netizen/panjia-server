-- ============================================================
-- 经纪人自购单提成（公司内部员工福利）
-- 登记：合同号 + 经纪人 → 总监审批 → 算薪时该经纪人在该合同号上的
--       结佣提点额外 +10 个百分点（仅该合同号下的该员工，其他合同/
--       其他参与经纪人提点不变；仅经纪人角色生效）
-- ============================================================

BEGIN;

-- ---------- 一、自购单表 ----------
CREATE TABLE IF NOT EXISTS pj_payroll_self_purchase (
    id                  BIGINT       PRIMARY KEY,
    contract_no         VARCHAR(100) NOT NULL,
    employee_id         BIGINT       NOT NULL,
    bonus_rate          NUMERIC(6,4) NOT NULL DEFAULT 0.1000,
    remark              VARCHAR(500),
    status              VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    process_instance_id VARCHAR(64),
    apply_by            BIGINT,
    apply_time          TIMESTAMP,
    approve_by          BIGINT,
    approve_time        TIMESTAMP,
    reject_reason       VARCHAR(500),
    version             INT          NOT NULL DEFAULT 0,
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 同一合同 + 同一经纪人，仅允许一条在途/已生效记录（撤销/驳回后可重新登记）
CREATE UNIQUE INDEX IF NOT EXISTS uk_sp_contract_emp_active
    ON pj_payroll_self_purchase(contract_no, employee_id)
    WHERE status IN ('DRAFT', 'SUBMITTED', 'APPROVED');

CREATE INDEX IF NOT EXISTS idx_sp_employee ON pj_payroll_self_purchase(employee_id, status);
CREATE INDEX IF NOT EXISTS idx_sp_status   ON pj_payroll_self_purchase(status);

COMMENT ON TABLE  pj_payroll_self_purchase IS '经纪人自购单提成（员工福利；审批通过后该合同号结佣提点额外加成）';
COMMENT ON COLUMN pj_payroll_self_purchase.contract_no IS '合同号（命中该合同号下的结佣明细）';
COMMENT ON COLUMN pj_payroll_self_purchase.employee_id IS '享受加成的经纪人（仅该员工、该合同号）';
COMMENT ON COLUMN pj_payroll_self_purchase.bonus_rate IS '提点加成（百分点绝对值，0.10=额外 +10 个百分点）';
COMMENT ON COLUMN pj_payroll_self_purchase.remark IS '备注/原因';
COMMENT ON COLUMN pj_payroll_self_purchase.status IS '状态 DRAFT=待提交 SUBMITTED=审批中 APPROVED=已通过 REJECTED=已驳回 CANCELLED=已撤销';
COMMENT ON COLUMN pj_payroll_self_purchase.process_instance_id IS 'Warm-Flow 流程实例 ID（self_purchase_approval）';
COMMENT ON COLUMN pj_payroll_self_purchase.apply_by IS '提交人用户 ID';
COMMENT ON COLUMN pj_payroll_self_purchase.apply_time IS '提交时间';
COMMENT ON COLUMN pj_payroll_self_purchase.approve_by IS '审批人（总监）用户 ID';
COMMENT ON COLUMN pj_payroll_self_purchase.approve_time IS '审批时间';
COMMENT ON COLUMN pj_payroll_self_purchase.reject_reason IS '驳回原因';
COMMENT ON COLUMN pj_payroll_self_purchase.version IS '乐观锁版本号';

-- ---------- 二、菜单与授权 ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761400000000002326, '自购单提成', 1761400000000002300, 9, 'selfpurchase', 'payroll/selfpurchase/index', NULL,
 'N', 'Y', 'C', '0', '0', 'payroll:selfpurchase:list', 'star', '', '',
 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '自购单提成菜单（合同号+经纪人登记，总监审批，算薪提点额外+10个点）'),
(1761400000000002327, '登记自购单', 1761400000000002326, 1, '', '', NULL,
 'N', 'Y', 'F', '0', '0', 'payroll:selfpurchase:add', '#', '', '',
 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '自购单提成-登记/修改/删除/提交按钮'),
(1761400000000002328, '撤销自购单', 1761400000000002326, 2, '', '', NULL,
 'N', 'Y', 'F', '0', '0', 'payroll:selfpurchase:cancel', '#', '', '',
 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '自购单提成-撤销按钮（撤回在途/作废已通过）')
ON CONFLICT (menu_id) DO NOTHING;

-- 超级管理员 + 总监 + 财务
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000001, 1761400000000002326),
(1761300000000000001, 1761400000000002327),
(1761300000000000001, 1761400000000002328),
(1761300000000000010, 1761400000000002326),
(1761300000000000010, 1761400000000002327),
(1761300000000000010, 1761400000000002328),
(1761300000000000012, 1761400000000002326),
(1761300000000000012, 1761400000000002327),
(1761300000000000012, 1761400000000002328)
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- ---------- 三、审批流程定义（发布态） ----------
-- 开始 → 提交申请(${initiator}) → 总监审核(role:1761300000000000010) → 结束；总监驳回回提交节点
-- ID 段：flow_definition 1762500000000000901；flow_node 0902~0905；flow_skip 0906~0909
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, "version", is_publish,
                             form_custom, form_path, activity_status, listener_type, listener_path, ext,
                             create_time, create_by, update_time, update_by, del_flag, tenant_id)
SELECT 1762500000000000901, 'self_purchase_approval', '自购单提成审批', 'CLASSICS', '1762300000000000200', '1', 1,
       'N', '/payroll/selfpurchase', 1, NULL, NULL, NULL,
       NOW(), '1761100000000000001', NULL, NULL, '0', '000000'
WHERE NOT EXISTS (SELECT 1 FROM flow_definition WHERE flow_code = 'self_purchase_approval');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio,
                      coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path,
                      "version", ext, del_flag, tenant_id, create_time, create_by)
SELECT 1762500000000000902, 0, 1762500000000000901, 'sp_start', '开始', NULL, '0.000',
       '200,200|200,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', NOW(), '1761100000000000001'
WHERE NOT EXISTS (SELECT 1 FROM flow_node WHERE id = 1762500000000000902);

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio,
                      coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path,
                      "version", ext, del_flag, tenant_id, create_time, create_by)
SELECT 1762500000000000903, 1, 1762500000000000901, 'sp_apply', '提交申请', '${initiator}', '0.000',
       '360,200|360,200', NULL, '', '', 'N', NULL, '1',
       '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', NOW(), '1761100000000000001'
WHERE NOT EXISTS (SELECT 1 FROM flow_node WHERE id = 1762500000000000903);

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio,
                      coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path,
                      "version", ext, del_flag, tenant_id, create_time, create_by)
SELECT 1762500000000000904, 1, 1762500000000000901, 'sp_review', '总监审核', 'role:1761300000000000010', '0.000',
       '540,200|540,200', NULL, '', '', 'N', NULL, '1',
       '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', NOW(), '1761100000000000001'
WHERE NOT EXISTS (SELECT 1 FROM flow_node WHERE id = 1762500000000000904);

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio,
                      coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path,
                      "version", ext, del_flag, tenant_id, create_time, create_by)
SELECT 1762500000000000905, 2, 1762500000000000901, 'sp_end', '结束', NULL, '0.000',
       '900,200|900,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', NOW(), '1761100000000000001'
WHERE NOT EXISTS (SELECT 1 FROM flow_node WHERE id = 1762500000000000905);

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type,
                       skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES
    (1762500000000000906, 1762500000000000901, 'sp_start',  0, 'sp_apply',  1, NULL, 'PASS', NULL, '280,200;320,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000907, 1762500000000000901, 'sp_apply',  1, 'sp_review', 1, NULL, 'PASS', NULL, '420,200;500,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000908, 1762500000000000901, 'sp_review', 1, 'sp_end',    2, '审核通过', 'PASS', NULL, '610,200;830,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000909, 1762500000000000901, 'sp_review', 1, 'sp_apply',  1, '驳回', 'REJECT', NULL, '560,320;400,320', NOW(), '1761100000000000001', '0', '000000')
ON CONFLICT (id) DO NOTHING;

COMMIT;
