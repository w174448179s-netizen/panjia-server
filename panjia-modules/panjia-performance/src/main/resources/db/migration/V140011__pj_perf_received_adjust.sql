-- ============================================================
-- 实收调整物理拆表：pj_perf_adjust（新签调整）→ pj_perf_received_adjust（实收调整）
-- 背景：
--   实收调整（原 adjust_type=RECEIVED_AMOUNT，口径 PERF_REAL，作用于 pj_received_detail）
--   历史上与新签调整共用 pj_perf_adjust 一张表，导致「新签调整」菜单能看到实收调整单。
--   现将实收调整独立成表、独立审批流（flowCode=perf_received_adjust，
--   bizType=PERF_RECEIVED_ADJUST）、独立接口 /perf/received-adjust/**。
-- 内容：
--   1) 建 pj_perf_received_adjust（在原调整单字段上去掉新签专属列，冗余 biz_type 便于列表筛选/展示）；
--   2) 审批流种子 perf_received_adjust（链路同 perf_adjust：申请人 → 总监审批，form_path 指实收调整菜单）；
--   3) 存量 RECEIVED_AMOUNT 单据（含在途单）原样迁入新表并回填 biz_type，再从 pj_perf_adjust 删除。
--      迁移在途单的工作流实例仍挂在 perf_adjust 流程定义下（实例按 businessId 定位），
--      审批回调由新签 listener 查无此单后委托实收调整服务兜底处理，无需改动 flow_* 数据。
-- ============================================================

BEGIN;

-- ---------- 一、实收调整单表 ----------
CREATE TABLE IF NOT EXISTS pj_perf_received_adjust (
    id                  BIGINT                 PRIMARY KEY,
    adjust_no           VARCHAR(32)            NOT NULL,
    period              VARCHAR(7)             NOT NULL,
    employee_id         BIGINT                 NOT NULL,
    dept_id             BIGINT                 NOT NULL,
    contract_no         VARCHAR(100),
    order_no            VARCHAR(100),
    biz_type            VARCHAR(50),
    payload_json        TEXT,
    target_amount       NUMERIC(18,2)          DEFAULT 0,
    original_amount     NUMERIC(18,2)          DEFAULT 0,
    reason              VARCHAR(500),
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

CREATE UNIQUE INDEX IF NOT EXISTS uk_radj_no ON pj_perf_received_adjust(adjust_no);
CREATE INDEX IF NOT EXISTS idx_radj_period   ON pj_perf_received_adjust(period, status);
CREATE INDEX IF NOT EXISTS idx_radj_employee ON pj_perf_received_adjust(employee_id, period);
CREATE INDEX IF NOT EXISTS idx_radj_dept     ON pj_perf_received_adjust(dept_id);
CREATE INDEX IF NOT EXISTS idx_radj_biztype  ON pj_perf_received_adjust(biz_type);
-- 合同维度在途互斥/聚合按 (contract_no, period)
CREATE INDEX IF NOT EXISTS idx_radj_contract_period ON pj_perf_received_adjust(contract_no, period, id);

COMMENT ON TABLE  pj_perf_received_adjust IS '实收调整单（PERF_REAL，独立于新签调整 pj_perf_adjust）';
COMMENT ON COLUMN pj_perf_received_adjust.adjust_no IS '调整单号（ADJ 前缀，与新签调整单号同规则但分表不冲突）';
COMMENT ON COLUMN pj_perf_received_adjust.biz_type IS '业务类型（一手房/房产金融/二手房等，创建时从实收合同回填，列表筛选/号源展示用）';
COMMENT ON COLUMN pj_perf_received_adjust.payload_json IS '分摊快照（AddMemberPayload：contractTotal/afterTotal/allocations，before+delta 绝对值语义）';

-- ---------- 二、实收调整审批流程（perf_received_adjust） ----------
-- 链路：开始 → 申请人(${initiator}) → 总监审批(role:1761300000000000010) → 结束
-- （与 perf_adjust 同审批链路；实收调整是发起后的二次调整，不经财务节点）
-- 审批通过后由 ReceivedAdjustWorkflowListener 自动执行实收调整。
-- ============================================================
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, form_path, activity_status, listener_type, listener_path, ext, create_time, create_by, update_time, update_by, del_flag, tenant_id)
SELECT 1762400000000000861, 'perf_received_adjust', '实收调整审批', 'CLASSICS', '1762300000000000200', '1', 1, 'N', '/performance/received-adjust', 1, NULL, NULL, NULL, now(), '1761100000000000001', NULL, NULL, '0', '000000'
WHERE NOT EXISTS (SELECT 1 FROM flow_definition WHERE flow_code = 'perf_received_adjust');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
SELECT 1762400000000000870, 0, 1762400000000000861, 'radj_start', '开始', NULL, '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001'
WHERE NOT EXISTS (SELECT 1 FROM flow_node WHERE id = 1762400000000000870);

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
SELECT 1762400000000000871, 1, 1762400000000000861, 'radj_applicant', '申请人', '${initiator}', '0.000', '360,200|360,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', now(), '1761100000000000001'
WHERE NOT EXISTS (SELECT 1 FROM flow_node WHERE id = 1762400000000000871);

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
SELECT 1762400000000000872, 1, 1762400000000000861, 'radj_director', '总监审批', 'role:1761300000000000010', '0.000', '540,200|540,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001'
WHERE NOT EXISTS (SELECT 1 FROM flow_node WHERE id = 1762400000000000872);

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
SELECT 1762400000000000873, 2, 1762400000000000861, 'radj_end', '结束', NULL, '0.000', '720,200|720,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001'
WHERE NOT EXISTS (SELECT 1 FROM flow_node WHERE id = 1762400000000000873);

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
SELECT 1762400000000000880, 1762400000000000861, 'radj_start', 0, 'radj_applicant', 1, NULL, 'PASS', NULL, '220,200;310,200', now(), '1761100000000000001', '0', '000000'
WHERE NOT EXISTS (SELECT 1 FROM flow_skip WHERE id = 1762400000000000880);

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
SELECT 1762400000000000881, 1762400000000000861, 'radj_applicant', 1, 'radj_director', 1, NULL, 'PASS', NULL, '410,200;490,200', now(), '1761100000000000001', '0', '000000'
WHERE NOT EXISTS (SELECT 1 FROM flow_skip WHERE id = 1762400000000000881);

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
SELECT 1762400000000000883, 1762400000000000861, 'radj_director', 1, 'radj_end', 2, NULL, 'PASS', NULL, '590,200;700,200', now(), '1761100000000000001', '0', '000000'
WHERE NOT EXISTS (SELECT 1 FROM flow_skip WHERE id = 1762400000000000883);

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
SELECT 1762400000000000884, 1762400000000000861, 'radj_director', 1, 'radj_applicant', 1, '驳回', 'REJECT', NULL, '540,200;360,200', now(), '1761100000000000001', '0', '000000'
WHERE NOT EXISTS (SELECT 1 FROM flow_skip WHERE id = 1762400000000000884);

-- ---------- 三、存量实收调整单迁移（保留原 ID，含在途单） ----------
INSERT INTO pj_perf_received_adjust (
    id, adjust_no, period, employee_id, dept_id, contract_no, order_no, biz_type,
    payload_json, target_amount, original_amount, reason, status, process_instance_id,
    applicant_id, approver_id, approve_time, operator_id, execute_time, create_time, update_time
)
SELECT a.id, a.adjust_no, a.period, a.employee_id, a.dept_id, a.contract_no, a.order_no, NULL,
       a.payload_json, a.target_amount, a.original_amount, a.reason, a.status, a.process_instance_id,
       a.applicant_id, a.approver_id, a.approve_time, a.operator_id, a.execute_time, a.create_time, a.update_time
FROM pj_perf_adjust a
WHERE a.adjust_type = 'RECEIVED_AMOUNT'
  AND NOT EXISTS (SELECT 1 FROM pj_perf_received_adjust r WHERE r.id = a.id);

-- 回填业务类型：按合同号/订单号双键从实收合同取（同 PERF_REAL 明细装载口径）
UPDATE pj_perf_received_adjust ra
SET biz_type = s.biz_type
FROM (
    SELECT ra2.id AS adjust_id,
           (SELECT rc.biz_type
              FROM pj_received_contract rc
             WHERE rc.contract_no = ra2.contract_no OR rc.order_no = ra2.contract_no
             LIMIT 1) AS biz_type
      FROM pj_perf_received_adjust ra2
     WHERE ra2.biz_type IS NULL
) s
WHERE ra.id = s.adjust_id AND s.biz_type IS NOT NULL;

-- 从新签调整表移除实收调整单（物理分流；在途单的流程实例按 businessId 路由，回调由实收服务兜底）
DELETE FROM pj_perf_adjust WHERE adjust_type = 'RECEIVED_AMOUNT';

COMMIT;
