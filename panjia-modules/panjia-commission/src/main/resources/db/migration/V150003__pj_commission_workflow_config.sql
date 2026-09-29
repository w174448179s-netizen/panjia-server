-- ============================================================
-- 结佣审批流程（commission_apply）定义最终态
-- 合并自 V150001,V150004
-- 说明：
--   1) admin 链种子里有旧版 commission_apply 定义（flow_definition id=1762400000000000301，
--      form_path=/performance/apply），本文件先删旧再建新，避免同 flow_code 两条已发布定义；
--      清库重建后本文件是旧版 301 流程重建的唯一来源，删除语句必须保留；
--   2) 新链路含 T-04 互斥网关：实收==应收时总监直跳结束（条件跳过财务）。
-- ============================================================

BEGIN;

-- ---------- 一、清理 admin 链种子的 V140 旧版定义 ----------
-- 旧链路：申请人→财务核验(commission_verify)→总监；需求 §3.1 改为 申请人→总监(capp_director)→财务(capp_finance)
DELETE FROM flow_skip WHERE definition_id = 1762400000000000301;
DELETE FROM flow_node WHERE definition_id = 1762400000000000301;
DELETE FROM flow_definition WHERE id = 1762400000000000301;

-- ---------- 二、新版流程定义 ----------
-- 链路：开始 → 申请人(${initiator}) → 总监审批 → [互斥]
--   实收==应收：直跳结束（T-04 条件跳过财务，见下方 skipCondition 连线）
--   有差异：财务审批 → 结束
-- 审批结果由 CommissionApplyWorkflowListener 回调：finish→锁定并发布结佣通过事件
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, form_path, activity_status, listener_type, listener_path, ext, create_time, create_by, update_time, update_by, del_flag, tenant_id)
VALUES (1762500000000000001, 'commission_apply', '结佣审批', 'CLASSICS', '1762300000000000200', '1', 1, 'N', '/workflow/processDefinition/index', 1, NULL, NULL, NULL, now(), '1761100000000000001', NULL, NULL, '0', '000000');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762500000000000010, 0, 1762500000000000001, 'capp_start', '开始', NULL, '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762500000000000011, 1, 1762500000000000001, 'capp_applicant', '申请人', '${initiator}', '0.000', '360,200|360,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762500000000000012, 1, 1762500000000000001, 'capp_director', '总监审批', 'role:1761300000000000010', '0.000', '540,200|540,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762500000000000013, 1, 1762500000000000001, 'capp_finance', '财务审批', 'role:1761300000000000012', '0.000', '720,200|720,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762500000000000014, 2, 1762500000000000001, 'capp_end', '结束', NULL, '0.000', '900,200|900,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

-- ---------- 三、流转连线 ----------
INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762500000000000020, 1762500000000000001, 'capp_start', 0, 'capp_applicant', 1, NULL, 'PASS', NULL, '220,200;310,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762500000000000021, 1762500000000000001, 'capp_applicant', 1, 'capp_director', 1, NULL, 'PASS', NULL, '410,200;490,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762500000000000022, 1762500000000000001, 'capp_director', 1, 'capp_finance', 1, NULL, 'PASS', NULL, '590,200;670,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762500000000000023, 1762500000000000001, 'capp_finance', 1, 'capp_end', 2, NULL, 'PASS', NULL, '770,200;880,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762500000000000024, 1762500000000000001, 'capp_director', 1, 'capp_applicant', 1, '驳回', 'REJECT', NULL, '540,200;360,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762500000000000025, 1762500000000000001, 'capp_finance', 1, 'capp_director', 1, '驳回', 'REJECT', NULL, '720,200;540,200', now(), '1761100000000000001', '0', '000000');

-- ---------- 四、T-04 条件跳过财务（合并自 V150004） ----------
-- skip_condition 使用 Warm-Flow 内置表达式 eq@@${realAmount}@@${expectedAmount}；
-- 流程变量 realAmount / expectedAmount 由 CommissionApplicationService 在发起流程时写入初值、
-- 总监办理前通过 ApprovalPort.setVariable 更新为最新值；网关跳过时 capp_finance 节点不会创建，监听器不触发
INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762500000000000026, 1762500000000000001, 'capp_director', 1, 'capp_end', 2, '实收应收无差异直跳结束', 'PASS', 'eq@@${realAmount}@@${expectedAmount}', '560,120;880,200', now(), '1761100000000000001', '0', '000000');

COMMIT;
