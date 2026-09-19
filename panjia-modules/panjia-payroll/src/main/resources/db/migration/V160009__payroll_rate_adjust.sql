-- ============================================================================
-- V160009 提成点调整（员工业绩扣点）+ 未买社保自动扣点
--
-- 业务（2026-08 客户三项业绩扣点需求）：
--   ① 未买社保扣 2 个点：员工档案社保参保事实（pj_people_salary_fact SOCIAL）
--      自动判断，人事维护档案即生效，免审批；扣点值走全局政策规则
--      noSocialDeduct（默认 -0.02，配置驱动，不硬编码）。
--   ② 人工调整项（电话考核未完成 / 个人调整等）：财务在「提成点调整」页登记
--      （原因必填）→ 发起 warm-flow 流程（rate_adjust_approval）→ 总监审批，
--      通过后按 start_month ~ end_month 区间在算薪时自动叠加到提成比例。
--
-- 算薪接入：
--   pj_payroll_detail 增加 manual_adjust（调整点数合计，负=扣点）与
--   rate_adjust_json（命中项溯源 JSON：未参保自动项 + 审批通过的人工项）。
--   引擎 finalRate = baseRate + perfDeduct + mentorAdd + manualAdjust
--   （EMPLOYEE 级提点覆盖后仍叠加调整）。
--
-- 流程（rate_adjust_approval）：开始 → 提交申请(${initiator}) → 总监审核 → 结束；
--   驳回回提交申请节点，重新提交走 completeAsSys。
--
-- ID 段位（全仓查重后）：
--   flow 种子 1762500000000000601~0609（结佣 001/010~014/020~026、payroll 101~109/
--   111~114/201/301~302、考勤 401~409、积分 501~509 均已占用，601~609 空闲）。
-- ============================================================================

BEGIN;

-- 1. 提成点调整单
CREATE TABLE pj_payroll_rate_adjust (
    id                  BIGINT        PRIMARY KEY,
    employee_id         BIGINT        NOT NULL,
    adjust_type         VARCHAR(32)   NOT NULL,
    adjust_rate         NUMERIC(8,4)  NOT NULL,
    start_month         VARCHAR(7)    NOT NULL,
    end_month           VARCHAR(7),
    reason              VARCHAR(500)  NOT NULL,
    status              VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',
    process_instance_id VARCHAR(64),
    apply_by            BIGINT,
    apply_time          TIMESTAMP,
    approve_by          BIGINT,
    approve_time        TIMESTAMP,
    reject_reason       VARCHAR(500),
    version             INT           NOT NULL DEFAULT 0,
    create_time         TIMESTAMP     NOT NULL DEFAULT NOW(),
    update_time         TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_prate_emp    ON pj_payroll_rate_adjust(employee_id, start_month);
CREATE INDEX idx_prate_status ON pj_payroll_rate_adjust(status);

COMMENT ON TABLE  pj_payroll_rate_adjust              IS '提成点调整单（员工业绩扣点，总监审批后按期间生效）';
COMMENT ON COLUMN pj_payroll_rate_adjust.adjust_type  IS '调整类型（字典 rate_adjust_type：NO_SOCIAL/PHONE_CHECK/PERSONAL）';
COMMENT ON COLUMN pj_payroll_rate_adjust.adjust_rate  IS '调整点数（负值=扣点，如 -0.02 扣 2 个点）';
COMMENT ON COLUMN pj_payroll_rate_adjust.start_month  IS '生效起始月 YYYY-MM（含）';
COMMENT ON COLUMN pj_payroll_rate_adjust.end_month    IS '生效结束月 YYYY-MM（含，null=长期有效至撤销）';
COMMENT ON COLUMN pj_payroll_rate_adjust.reason       IS '调整原因（必填）';
COMMENT ON COLUMN pj_payroll_rate_adjust.status       IS '状态：DRAFT/SUBMITTED/APPROVED/REJECTED/CANCELLED';

-- 2. 工资明细增加提成点调整溯源列
ALTER TABLE pj_payroll_detail
    ADD COLUMN IF NOT EXISTS manual_adjust   NUMERIC(8,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS rate_adjust_json TEXT;

COMMENT ON COLUMN pj_payroll_detail.manual_adjust    IS '提成点调整合计（未参保自动扣点+审批通过人工项，负=扣点）';
COMMENT ON COLUMN pj_payroll_detail.rate_adjust_json IS '提成点调整命中项溯源JSON（adjustId/type/rate/reason/source）';

-- 3. 全局政策规则增加未买社保扣点参数（幂等：已存在则不覆盖）
UPDATE pj_payroll_policy_rule
SET rule_content = rule_content || '{"noSocialDeduct":-0.02}'::jsonb,
    update_time  = NOW()
WHERE scope_type = 'GLOBAL'
  AND NOT (rule_content ? 'noSocialDeduct');

-- 4. 流程定义（提成点调整审批，发布态）
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, version, is_publish,
                             form_custom, form_path, activity_status, create_time, create_by, del_flag, tenant_id)
VALUES (1762500000000000601, 'rate_adjust_approval', '提成点调整审批', 'CLASSICS', '1762300000000000200',
        '1', 1, 'N', '/payroll/rateadjust', 1, NOW(), '1761100000000000001', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

-- 5. 流程节点：开始 → 提交申请（申请人）→ 总监审核 → 结束
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

-- 6. 节点跳转：提交 → 总监审核；总监审核 通过→结束 / 驳回→回提交
INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type,
                       skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES
    (1762500000000000606, 1762500000000000601, 'ra_start',  0, 'ra_apply',  1, NULL, 'PASS', NULL, '280,200;320,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000607, 1762500000000000601, 'ra_apply',  1, 'ra_review', 1, NULL, 'PASS', NULL, '420,200;500,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000608, 1762500000000000601, 'ra_review', 1, 'ra_end',    2, '审核通过', 'PASS', NULL, '610,200;830,200', NOW(), '1761100000000000001', '0', '000000'),
    (1762500000000000609, 1762500000000000601, 'ra_review', 1, 'ra_apply',  1, '驳回', 'REJECT', NULL, '560,320;400,320', NOW(), '1761100000000000001', '0', '000000')
    ON CONFLICT (id) DO NOTHING;

COMMIT;
