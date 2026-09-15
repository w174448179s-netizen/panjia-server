-- =====================================================
-- 业绩调整流程 form_path 兜底修正（V170003）
-- =====================================================
-- 问题：
--   用户在「我发起的」点【查看】时报错：
--     "未找到业务页面「/workflow/processDefinition/index」"
--   根因：V140002__pj_perf_init.sql 的 flow_definition 种子
--   写入了占位错误值 '/workflow/processDefinition/index'。
--
--   V100007__fix_flow_form_path_and_menu_perms_v2.sql 已包含
--   同样的修正，但若 V100007 因故未执行（或数据库被清库重建后
--   V140002 在 V100007 之后再次写入错误值），form_path 仍是
--   占位值，用户无法跳转到业务页。
--
-- 修复策略：
--   本脚本作为「最高版本号仲裁者」，幂等 UPDATE form_path 到
--   正确业务页路径。Flyway 升序执行，本脚本最后生效，确保
--   无论库状态如何，跑完迁移后 form_path 必然正确。
--
-- 正确 form_path 对照（与 V100007 保持一致）：
--   perf_adjust      → /performance/adjustment
--   perf_received    → /performance/received
--   commission_apply → /performance/apply
--   commission_adjust→ /performance/adjust
--   bonus_apply      → /payroll/bonus
--   payroll_batch    → /payroll/batch
--   payroll_supplement → /payroll/adjust
--
-- 【幂等】全部 UPDATE ... WHERE form_path IS DISTINCT FROM ...
--   重复执行不会产生副作用，仅更新当前值确实不正确的行。
-- =====================================================

BEGIN;

UPDATE flow_definition SET form_path = '/performance/adjustment', update_time = now()
 WHERE flow_code = 'perf_adjust'       AND form_path IS DISTINCT FROM '/performance/adjustment';
UPDATE flow_definition SET form_path = '/performance/received', update_time = now()
 WHERE flow_code = 'perf_received'     AND form_path IS DISTINCT FROM '/performance/received';
UPDATE flow_definition SET form_path = '/performance/apply', update_time = now()
 WHERE flow_code = 'commission_apply'  AND form_path IS DISTINCT FROM '/performance/apply';
UPDATE flow_definition SET form_path = '/performance/adjust', update_time = now()
 WHERE flow_code = 'commission_adjust' AND form_path IS DISTINCT FROM '/performance/adjust';
UPDATE flow_definition SET form_path = '/payroll/bonus', update_time = now()
 WHERE flow_code = 'bonus_apply'       AND form_path IS DISTINCT FROM '/payroll/bonus';
UPDATE flow_definition SET form_path = '/payroll/batch', update_time = now()
 WHERE flow_code = 'payroll_batch'     AND form_path IS DISTINCT FROM '/payroll/batch';
UPDATE flow_definition SET form_path = '/payroll/adjust', update_time = now()
 WHERE flow_code = 'payroll_supplement' AND form_path IS DISTINCT FROM '/payroll/adjust';

-- 节点级 form_path 优先级高于定义级，防御性一并修正
UPDATE flow_node SET form_path = '/performance/adjustment'
 WHERE form_path IS NOT NULL
   AND definition_id IN (SELECT id FROM flow_definition WHERE flow_code = 'perf_adjust')
   AND form_path IS DISTINCT FROM '/performance/adjustment';
UPDATE flow_node SET form_path = '/performance/received'
 WHERE form_path IS NOT NULL
   AND definition_id IN (SELECT id FROM flow_definition WHERE flow_code = 'perf_received')
   AND form_path IS DISTINCT FROM '/performance/received';

COMMIT;
