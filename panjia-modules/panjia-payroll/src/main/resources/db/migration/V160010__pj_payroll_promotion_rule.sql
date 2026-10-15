-- ============================================================
-- V160010: 职级自动晋升/降级规则 + 职级变动通知流程（2026-10 生效）
-- 业务口径（已与客户确认，锁定）：
--   A0（2500底薪+20%，保护期3个月）→ 保护期最后一个月算薪时判定：
--     A0 期间累计业绩 < 10000 → 次月进 A1；≥ 10000 → 次月直接晋 A2
--   A1 期间累计业绩 ≥ 10000  → 次月晋 A2
--   A2 期间累计业绩 ≥ 100000 → 次月晋 A3
--   累计业绩 = pj_perf_fact.performanceAmount 原始金额求和（不折算），
--   区间 = 当前 LEVEL 事实 effective_date 所在月 ~ 算薪批次月（含）；
--   新职级一律次月 1 日生效，本次算薪仍按旧职级；
--   无 promotion 节点的职级不参与自动晋升。
-- 阈值/保护期/去向全部配置化：pj_payroll_rank_rule.rule_content.promotion
-- ============================================================

-- 1. A0/A1/A2 新版规则行（V160009 插入，2026-10-01 起）合并 promotion 节点
--    jsonb || 顶层合并，重复执行幂等
UPDATE pj_payroll_rank_rule
SET rule_content = rule_content
    || '{"promotion":{"protectMonths":3,"threshold":10000,"belowTo":"A1","reachTo":"A2"}}'::jsonb,
    update_time = NOW()
WHERE id = 1762500000000000151;

UPDATE pj_payroll_rank_rule
SET rule_content = rule_content
    || '{"promotion":{"threshold":10000,"reachTo":"A2"}}'::jsonb,
    update_time = NOW()
WHERE id = 1762500000000000152;

UPDATE pj_payroll_rank_rule
SET rule_content = rule_content
    || '{"promotion":{"threshold":100000,"reachTo":"A3"}}'::jsonb,
    update_time = NOW()
WHERE id = 1762500000000000153;

-- 2. 职级变动通知流程（payroll_promotion）
-- 链路：开始 → 职级变动知会(财务/总监) → 结束（纯待办通知，办理即结束，无业务状态回写）
-- 种子口径与 V100003 保持一致；flow 表归 ruoyi-admin 迁移管辖，
-- 本模块种子遵循 panjia-performance V140002 的先例（模块迁移自带流程定义）
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, form_path, activity_status, listener_type, listener_path, ext, create_time, create_by, update_time, update_by, del_flag, tenant_id)
VALUES (1762500000000000801, 'payroll_promotion', '职级变动通知', 'CLASSICS', '1762300000000000200', '1', 1, 'N', '/payroll/batch', 1, NULL, NULL, NULL, now(), '1761100000000000001', NULL, NULL, '0', '000000')
ON CONFLICT (id) DO NOTHING;

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762500000000000810, 0, 1762500000000000801, 'promotion_start', '开始', NULL, '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001')
ON CONFLICT (id) DO NOTHING;

-- 知会节点：财务(role:1761300000000000012) + 总监(role:1761300000000000010)
INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762500000000000811, 1, 1762500000000000801, 'promotion_notice', '职级变动知会', 'role:1761300000000000012@@role:1761300000000000010', '0.000', '420,200|420,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"termination,file"}]', '0', '000000', now(), '1761100000000000001')
ON CONFLICT (id) DO NOTHING;

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762500000000000812, 2, 1762500000000000801, 'promotion_end', '结束', NULL, '0.000', '640,200|640,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001')
ON CONFLICT (id) DO NOTHING;

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762500000000000820, 1762500000000000801, 'promotion_start', 0, 'promotion_notice', 1, NULL, 'PASS', NULL, '260,200;360,200', now(), '1761100000000000001', '0', '000000')
ON CONFLICT (id) DO NOTHING;

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762500000000000821, 1762500000000000801, 'promotion_notice', 1, 'promotion_end', 2, NULL, 'PASS', NULL, '480,200;580,200', now(), '1761100000000000001', '0', '000000')
ON CONFLICT (id) DO NOTHING;
