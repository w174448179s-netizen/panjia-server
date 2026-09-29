-- ============================================================================
-- V100003 盘家智管 工作流种子与系统参数最终态（清库重建合并版）
-- 内容：Warm-Flow 流程分类/定义/节点/流转 + sys_config 参数 + 业务字典。
-- 推演要点：
--   · form_path 修复链（V100001 占位值 → V100006/V100007/V100009 修正）已折叠：
--     本文件自建的 4 个流程定义直接写入最终 form_path；
--     perf_adjust/perf_received/commission_apply 由其他模块迁移创建
--     （V140002/V150001），保留幂等修正 UPDATE 作为最终态兜底
--   · 总监审批节点 72h 自动通过（V100010）：perf_director/rcv_director/capp_director
--   · 结佣审批财务驳回改回申请人（V100024）：capp_finance REJECT → capp_applicant
--   · 结佣申请审批(commission_apply, 301) 定义已不在此写入：V100001 建，
--     panjia-commission V150001「删旧建新」（新定义 id 1762500000000000001，节点 capp_*），
--     INSERT→DELETE 完全抵消，归属 commission 模块合并文件
-- 合并自：V100001,V100006,V100007,V100009,V100010,V100011,V100016,V100021,
--         V100024,V100027（字典部分）
-- ============================================================================

-- ============================================================
-- 一、流程分类（V100001）
-- ============================================================
INSERT INTO flow_category (category_id, parent_id, ancestors, category_name, order_num, del_flag, create_dept, create_by, create_time)
VALUES (1762300000000000200, 0, '', '薪酬审批', 1, '0', 1761000000000000100, 1761100000000000001, now());

-- ============================================================
-- 二、流程定义/节点/流转（V100001 种子；form_path 为 V100006/V100007/V100009 修复链最终值）
-- ============================================================

-- ---------- 流程：结佣调整审批（commission_adjust） ----------
-- 链路：开始 → 申请人(${initiator}) → 核验(财务) → 总监审批 → 结束
-- 支持跳过核验开关：#{skip_verify == true}
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, form_path, activity_status, listener_type, listener_path, ext, create_time, create_by, update_time, update_by, del_flag, tenant_id)
VALUES (1762400000000000501, 'commission_adjust', '结佣调整审批', 'CLASSICS', '1762300000000000200', '1', 1, 'N', '/performance/adjust', 1, NULL, NULL, NULL, now(), '1761100000000000001', NULL, NULL, '0', '000000');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000510, 0, 1762400000000000501, 'adjust_start', '开始', NULL, '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000511, 1, 1762400000000000501, 'adjust_applicant', '申请人', '${initiator}', '0.000', '360,200|360,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000512, 1, 1762400000000000501, 'adjust_verify', '核验(财务)', 'role:1761300000000000012', '0.000', '540,200|540,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000513, 1, 1762400000000000501, 'adjust_director', '总监审批', 'role:1761300000000000010', '0.000', '720,200|720,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000514, 2, 1762400000000000501, 'adjust_end', '结束', NULL, '0.000', '900,200|900,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000520, 1762400000000000501, 'adjust_start', 0, 'adjust_applicant', 1, NULL, 'PASS', NULL, '220,200;310,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000521, 1762400000000000501, 'adjust_applicant', 1, 'adjust_verify', 1, NULL, 'PASS', NULL, '410,200;490,200', now(), '1761100000000000001', '0', '000000');

-- 跳过核验直接总监审批（通过 skip_condition 控制开关）
INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000522, 1762400000000000501, 'adjust_applicant', 1, 'adjust_director', 1, '跳过核验', 'PASS', '#{skip_verify == true}', '410,200;670,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000523, 1762400000000000501, 'adjust_verify', 1, 'adjust_director', 1, NULL, 'PASS', NULL, '590,200;670,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000524, 1762400000000000501, 'adjust_director', 1, 'adjust_end', 2, NULL, 'PASS', NULL, '770,200;880,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000525, 1762400000000000501, 'adjust_director', 1, 'adjust_applicant', 1, '驳回', 'REJECT', NULL, '720,200;360,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000526, 1762400000000000501, 'adjust_verify', 1, 'adjust_applicant', 1, '核验驳回', 'REJECT', NULL, '540,200;360,200', now(), '1761100000000000001', '0', '000000');

-- ---------- 流程：奖金录入审批（bonus_apply） ----------
-- 链路：开始 → 申请人(店长/总监) → 总监审批 → 结束
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, form_path, activity_status, listener_type, listener_path, ext, create_time, create_by, update_time, update_by, del_flag, tenant_id)
VALUES (1762400000000000401, 'bonus_apply', '奖金录入审批', 'CLASSICS', '1762300000000000200', '1', 1, 'N', '/payroll/bonus', 1, NULL, NULL, NULL, now(), '1761100000000000001', NULL, NULL, '0', '000000');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000410, 0, 1762400000000000401, 'bonus_start', '开始', NULL, '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000411, 1, 1762400000000000401, 'bonus_applicant', '申请人', 'role:1761300000000000011@@role:1761300000000000010', '0.000', '360,200|360,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000412, 1, 1762400000000000401, 'bonus_director', '总监审批', 'role:1761300000000000010', '0.000', '540,200|540,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000413, 2, 1762400000000000401, 'bonus_end', '结束', NULL, '0.000', '900,200|900,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000420, 1762400000000000401, 'bonus_start', 0, 'bonus_applicant', 1, NULL, 'PASS', NULL, '220,200;310,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000421, 1762400000000000401, 'bonus_applicant', 1, 'bonus_director', 1, NULL, 'PASS', NULL, '410,200;490,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000422, 1762400000000000401, 'bonus_director', 1, 'bonus_end', 2, NULL, 'PASS', NULL, '590,200;880,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000423, 1762400000000000401, 'bonus_director', 1, 'bonus_applicant', 1, '驳回', 'REJECT', NULL, '540,200;360,200', now(), '1761100000000000001', '0', '000000');

-- ---------- 流程：算薪批次审批（payroll_batch） ----------
-- 链路：开始 → 提交人(财务/店长) → 总监审核 → 总监锁定 → 结束
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, form_path, activity_status, listener_type, listener_path, ext, create_time, create_by, update_time, update_by, del_flag, tenant_id)
VALUES (1762400000000000601, 'payroll_batch', '算薪批次审批', 'CLASSICS', '1762300000000000200', '1', 1, 'N', '/payroll/batch', 1, NULL, NULL, NULL, now(), '1761100000000000001', NULL, NULL, '0', '000000');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000610, 0, 1762400000000000601, 'payroll_start', '开始', NULL, '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000611, 1, 1762400000000000601, 'payroll_submit', '提交算薪', 'role:1761300000000000012@@role:1761300000000000011', '0.000', '360,200|360,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000612, 1, 1762400000000000601, 'payroll_review', '总监审核', 'role:1761300000000000010', '0.000', '540,200|540,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000613, 1, 1762400000000000601, 'payroll_lock', '总监锁定', 'role:1761300000000000010', '0.000', '720,200|720,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"termination,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000614, 2, 1762400000000000601, 'payroll_end', '结束', NULL, '0.000', '900,200|900,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000620, 1762400000000000601, 'payroll_start', 0, 'payroll_submit', 1, NULL, 'PASS', NULL, '220,200;310,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000621, 1762400000000000601, 'payroll_submit', 1, 'payroll_review', 1, NULL, 'PASS', NULL, '410,200;490,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000622, 1762400000000000601, 'payroll_review', 1, 'payroll_lock', 1, '审核通过', 'PASS', NULL, '590,200;670,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000623, 1762400000000000601, 'payroll_lock', 1, 'payroll_end', 2, NULL, 'PASS', NULL, '770,200;880,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000624, 1762400000000000601, 'payroll_review', 1, 'payroll_submit', 1, '驳回', 'REJECT', NULL, '540,200;360,200', now(), '1761100000000000001', '0', '000000');

-- ---------- 流程：补发单审批（payroll_supplement） ----------
-- 链路：开始 → 申请人(${initiator}) → 核验(财务) → 总监审批 → 结束
-- 支持跳过核验开关：#{skip_verify == true}
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, form_path, activity_status, listener_type, listener_path, ext, create_time, create_by, update_time, update_by, del_flag, tenant_id)
VALUES (1762400000000000701, 'payroll_supplement', '补发单审批', 'CLASSICS', '1762300000000000200', '1', 1, 'N', '/payroll/adjust', 1, NULL, NULL, NULL, now(), '1761100000000000001', NULL, NULL, '0', '000000');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000710, 0, 1762400000000000701, 'supplement_start', '开始', NULL, '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000711, 1, 1762400000000000701, 'supplement_applicant', '申请人', '${initiator}', '0.000', '360,200|360,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000712, 1, 1762400000000000701, 'supplement_verify', '核验(财务)', 'role:1761300000000000012', '0.000', '540,200|540,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000713, 1, 1762400000000000701, 'supplement_director', '总监审批', 'role:1761300000000000010', '0.000', '720,200|720,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000714, 2, 1762400000000000701, 'supplement_end', '结束', NULL, '0.000', '900,200|900,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000720, 1762400000000000701, 'supplement_start', 0, 'supplement_applicant', 1, NULL, 'PASS', NULL, '220,200;310,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000721, 1762400000000000701, 'supplement_applicant', 1, 'supplement_verify', 1, NULL, 'PASS', NULL, '410,200;490,200', now(), '1761100000000000001', '0', '000000');

-- 跳过核验直接总监审批（通过 skip_condition 控制开关）
INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000722, 1762400000000000701, 'supplement_applicant', 1, 'supplement_director', 1, '跳过核验', 'PASS', '#{skip_verify == true}', '410,200;670,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000723, 1762400000000000701, 'supplement_verify', 1, 'supplement_director', 1, NULL, 'PASS', NULL, '590,200;670,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000724, 1762400000000000701, 'supplement_director', 1, 'supplement_end', 2, NULL, 'PASS', NULL, '770,200;880,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000725, 1762400000000000701, 'supplement_director', 1, 'supplement_applicant', 1, '驳回', 'REJECT', NULL, '720,200;360,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000726, 1762400000000000701, 'supplement_verify', 1, 'supplement_applicant', 1, '核验驳回', 'REJECT', NULL, '540,200;360,200', now(), '1761100000000000001', '0', '000000');

-- ============================================================
-- 三、form_path 修复链最终值（V100006→V100007→V100009，幂等 UPDATE 兜底）
-- perf_adjust/perf_received 定义由 panjia-performance V140002 创建；
-- commission_apply 由 panjia-commission V150001 删旧建新。
-- 这些定义的种子晚于本文件执行，故保留 UPDATE 兜底；对应模块的合并文件
-- 应直接写入下方最终值。
-- ============================================================

UPDATE flow_definition SET form_path = '/performance/adjustment', update_time = now()
 WHERE flow_code = 'perf_adjust'       AND form_path IS DISTINCT FROM '/performance/adjustment';
UPDATE flow_definition SET form_path = '/performance/received', update_time = now()
 WHERE flow_code = 'perf_received'     AND form_path IS DISTINCT FROM '/performance/received';
UPDATE flow_definition SET form_path = '/performance/apply', update_time = now()
 WHERE flow_code = 'commission_apply'  AND form_path IS DISTINCT FROM '/performance/apply';

-- 节点级 form_path 优先级高于定义级，防御性一并修正（V100009）
UPDATE flow_node SET form_path = '/performance/adjustment'
 WHERE form_path IS NOT NULL
   AND definition_id IN (SELECT id FROM flow_definition WHERE flow_code = 'perf_adjust')
   AND form_path IS DISTINCT FROM '/performance/adjustment';
UPDATE flow_node SET form_path = '/performance/received'
 WHERE form_path IS NOT NULL
   AND definition_id IN (SELECT id FROM flow_definition WHERE flow_code = 'perf_received')
   AND form_path IS DISTINCT FROM '/performance/received';

-- ============================================================
-- 四、总监审批节点 72 小时自动通过（V100010）
-- 覆盖：业绩调整(perf_director)、新签/实收审批(rcv_director)、结佣申请审批(capp_director)
-- （三个节点均由其他模块迁移创建；幂等：ext NOT LIKE '%AutoApproval%'）
-- ============================================================

UPDATE flow_node
SET ext = REPLACE(ext, ']', ',{"code":"AutoApproval","value":"hours=72,skipType=PASS"}]')
WHERE node_code IN ('perf_director', 'rcv_director', 'capp_director')
  AND ext IS NOT NULL
  AND ext NOT LIKE '%AutoApproval%';

-- ============================================================
-- 五、结佣审批驳回路径修正（V100024）
-- 财务核验驳回 → 直回申请人（原先驳回会走总监再审批）；幂等
-- （capp_* 节点由 panjia-commission V150001 创建，其合并文件需保留本最终值）
-- ============================================================

UPDATE flow_skip s
SET next_node_code = 'capp_applicant',
    update_time    = CURRENT_TIMESTAMP
FROM flow_definition d
WHERE s.definition_id = d.id
  AND d.flow_code = 'commission_apply'
  AND s.now_node_code = 'capp_finance'
  AND s.skip_type = 'REJECT'
  AND s.next_node_code = 'capp_director';

-- ============================================================
-- 六、系统参数配置（V100001 ×3、V100011、V100016）
-- 跳过核验开关：发起流程时读取此参数，传入流程变量 skip_verify
-- ============================================================

INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type, create_dept, create_by, create_time, remark)
VALUES (1761500000000000001, '结佣申请审批-跳过核验', 'panjia.workflow.commission_apply.skip_verify', 'false', 'Y', 1761000000000000100, 1761100000000000001, now(), '结佣申请审批是否跳过核验(财务)直接总监审批，true=跳过，false=不跳过');

INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type, create_dept, create_by, create_time, remark)
VALUES (1761500000000000002, '结佣调整审批-跳过核验', 'panjia.workflow.commission_adjust.skip_verify', 'false', 'Y', 1761000000000000100, 1761100000000000001, now(), '结佣调整审批是否跳过核验(财务)直接总监审批，true=跳过，false=不跳过');

INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type, create_dept, create_by, create_time, remark)
VALUES (1761500000000000003, '补发单审批-跳过核验', 'panjia.workflow.payroll_supplement.skip_verify', 'false', 'Y', 1761000000000000100, 1761100000000000001, now(), '补发单审批是否跳过核验(财务)直接总监审批，true=跳过，false=不跳过');

-- 结佣申请核验环节优化（V100011）：实收=应收 时跳过财务核验
INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type, create_dept, create_by, create_time, remark)
VALUES (1761600000000000011, '结佣-实收应收无差异跳过财务', 'panjia.commission.skip_finance_when_match', 'true', 'Y', 1761000000000000100, 1761100000000000001, now(),
        'true=总监审批后实收应收无差异时自动跳过财务节点；false=强制走财务人工审批')
ON CONFLICT (config_id) DO NOTHING;

-- 提成点调整差异容忍（V100016）
INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type, create_dept, create_by, create_time, remark)
VALUES (1761600000000000016, '结佣-实收应收差异容忍阈值', 'panjia.commission.diff_tolerance', '1', 'Y', 1761000000000000100, 1761100000000000001, now(),
        '实收与应收金额差异容忍阈值（元），|实收-应收|<=该值视为无差异，不对齐、跳过财务节点；默认1')
ON CONFLICT (config_id) DO NOTHING;

-- ============================================================
-- 七、业务字典（V100027：提成点调整类型）+ 工作流状态文案（V100021）
-- ============================================================

-- ---------- 字典类型：提成点调整类型（V100027） ----------
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761500000000000501, '提成点调整类型', 'rate_adjust_type', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '员工业绩提成点调整类型')
ON CONFLICT (dict_id) DO NOTHING;

-- ---------- 字典数据（V100027） ----------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761500000000000511, 1, '未买社保扣点', 'NO_SOCIAL',   'rate_adjust_type', NULL, 'warning', 'N', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '档案参保事实自动判断，免审批，不在调整页登记'),
(1761500000000000512, 2, '电话考核扣点', 'PHONE_CHECK', 'rate_adjust_type', NULL, 'primary', 'N', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '电话考核未完成的业绩扣点'),
(1761500000000000513, 3, '个人调整扣点', 'PERSONAL',    'rate_adjust_type', NULL, 'danger',  'N', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '针对个人的业绩扣点调整')
ON CONFLICT (dict_code) DO NOTHING;

-- ---------- 工作流状态文案（V100021）：waiting（运行中）统一叫「审批中」，幂等 ----------
UPDATE sys_dict_data
SET dict_label = '审批中',
    update_by  = 1761100000000000001,
    update_time = now()
WHERE dict_type = 'wf_business_status'
  AND dict_value = 'waiting';
