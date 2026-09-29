-- ============================================================
-- 业绩域 sys_config 参数与审批流配置最终态（清库重建一次性执行版）
-- 合并自：V140002,V140004
-- 说明：
--   1) V140002 尾部的 sys_config 参数与 perf_adjust/perf_received 审批流种子移入本文件；
--   2) flow_definition.form_path 直接落最终业务页路径
--      （perf_adjust=/performance/adjustment，perf_received=/performance/received）：
--      原 V140002 写入的 '/workflow/processDefinition/index' 为占位错误值，
--      ruoyi-admin V100006/V100007/V100009 均修正为上述路径，但其版本号小于
--      V140002，清库重建时 UPDATE 先于 INSERT 执行不会生效，故此处直接写最终值；
--   3) V100010 曾向 perf_director/rcv_director 节点 ext 追加 AutoApproval(72h)，
--      已被 V140002 的 panjia.flow.director_timeout_hours 配置化方案取代，不吸收。
-- ============================================================

BEGIN;

-- ---------- 一、审批流程参数（需求文档 §5） ----------
INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type, create_dept, create_by, create_time, remark)
VALUES (1761600000000000001, '是否跳过财务审批节点', 'panjia.flow.skip_finance', 'false', 'Y', 1761000000000000100, 1761100000000000001, now(),
        'true=实收/结佣审批均跳过财务节点；false=按标准链路流转')
ON CONFLICT (config_id) DO NOTHING;
INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type, create_dept, create_by, create_time, remark)
VALUES (1761600000000000002, '总监审批自动通过超时(小时)', 'panjia.flow.director_timeout_hours', '0', 'Y', 1761000000000000100, 1761100000000000001, now(),
        '总监节点任务超过该小时数未办理则系统自动审批通过；0=关闭')
ON CONFLICT (config_id) DO NOTHING;

-- ---------- 二、新签调整审批流程（perf_adjust） ----------
-- 链路：开始 → 申请人(${initiator}) → 总监审批(role:1761300000000000010) → 结束
-- 审批通过后由 AdjustWorkflowListener 自动执行业绩调整
-- ============================================================
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, form_path, activity_status, listener_type, listener_path, ext, create_time, create_by, update_time, update_by, del_flag, tenant_id)
VALUES (1762400000000000801, 'perf_adjust', '新签调整审批', 'CLASSICS', '1762300000000000200', '1', 1, 'N', '/performance/adjustment', 1, NULL, NULL, NULL, now(), '1761100000000000001', NULL, NULL, '0', '000000');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000810, 0, 1762400000000000801, 'perf_start', '开始', NULL, '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000811, 1, 1762400000000000801, 'perf_applicant', '申请人', '${initiator}', '0.000', '360,200|360,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000812, 1, 1762400000000000801, 'perf_director', '总监审批', 'role:1761300000000000010', '0.000', '540,200|540,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000813, 2, 1762400000000000801, 'perf_end', '结束', NULL, '0.000', '720,200|720,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000820, 1762400000000000801, 'perf_start', 0, 'perf_applicant', 1, NULL, 'PASS', NULL, '220,200;310,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000821, 1762400000000000801, 'perf_applicant', 1, 'perf_director', 1, NULL, 'PASS', NULL, '410,200;490,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000823, 1762400000000000801, 'perf_director', 1, 'perf_end', 2, NULL, 'PASS', NULL, '590,200;700,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000824, 1762400000000000801, 'perf_director', 1, 'perf_applicant', 1, '驳回', 'REJECT', NULL, '540,200;360,200', now(), '1761100000000000001', '0', '000000');

-- ---------- 三、实收业绩审批流程（perf_received） ----------
-- 链路（需求文档 §2.1/§2.2）：
--   开始 → 申请人(${initiator}) → 财务审批(role:finance) → 总监审批(role:director) → 结束
--   导入自动提交 / 店长发起：走完整链路（自动提交时申请人节点由系统完成）
--   财务发起：后端自动完成财务节点，直达总监
--   总监发起：后端连续完成财务+总监节点，直接落点
--   panjia.flow.skip_finance=true 时财务节点由系统自动完成
-- 审批结果由 ReceivedWorkflowListener 回调：finish→APPROVED，back→REJECTED
-- ============================================================
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, form_path, activity_status, listener_type, listener_path, ext, create_time, create_by, update_time, update_by, del_flag, tenant_id)
VALUES (1762400000000000901, 'perf_received', '实收业绩审批', 'CLASSICS', '1762300000000000200', '1', 1, 'N', '/performance/received', 1, NULL, NULL, NULL, now(), '1761100000000000001', NULL, NULL, '0', '000000');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000910, 0, 1762400000000000901, 'rcv_start', '开始', NULL, '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000911, 1, 1762400000000000901, 'rcv_applicant', '申请人', '${initiator}', '0.000', '360,200|360,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000912, 1, 1762400000000000901, 'rcv_finance', '财务审批', 'role:1761300000000000012', '0.000', '540,200|540,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000913, 1, 1762400000000000901, 'rcv_director', '总监审批', 'role:1761300000000000010', '0.000', '720,200|720,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000914, 2, 1762400000000000901, 'rcv_end', '结束', NULL, '0.000', '900,200|900,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000920, 1762400000000000901, 'rcv_start', 0, 'rcv_applicant', 1, NULL, 'PASS', NULL, '220,200;310,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000921, 1762400000000000901, 'rcv_applicant', 1, 'rcv_finance', 1, NULL, 'PASS', NULL, '410,200;490,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000922, 1762400000000000901, 'rcv_finance', 1, 'rcv_director', 1, NULL, 'PASS', NULL, '590,200;670,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000923, 1762400000000000901, 'rcv_director', 1, 'rcv_end', 2, NULL, 'PASS', NULL, '770,200;880,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000924, 1762400000000000901, 'rcv_finance', 1, 'rcv_applicant', 1, '驳回', 'REJECT', NULL, '540,200;360,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000925, 1762400000000000901, 'rcv_director', 1, 'rcv_applicant', 1, '驳回', 'REJECT', NULL, '720,200;360,200', now(), '1761100000000000001', '0', '000000');

-- ---------- 四、业绩域参数：经纪人折算比例 ----------
-- 贝壳新签明细表中的当月应收/实收为折算后「当前金额」，
-- 业绩事实的原始金额 origin_amount = 当前金额 / 经纪人折算比例。
-- 参数落 sys_config（RuoYi 参数配置表），默认 0.85（85%），
-- 目前只供后端计算落库，不提供界面维护；如需调整直接改参数值。
-- 幂等：按 config_key 判重，重复执行不报错。
INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type,
                        create_dept, create_by, create_time, remark)
SELECT 1761500000000000004,
       '业绩-经纪人折算比例',
       'panjia.performance.broker_conversion_rate',
       '0.85',
       'Y',
       1761000000000000100,
       1761100000000000001,
       now(),
       '经纪人业绩折算比例，原始金额=贝壳当前金额÷该比例（默认0.85，即85%）'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_config WHERE config_key = 'panjia.performance.broker_conversion_rate'
);

COMMIT;
