-- ============================================================
-- Task-0-6: U-Undo 脚本规约示例
-- 对应：V100002__pj_import_template.sql（建表）
-- 归属域：panjia-import
-- 说明：本地调试用回滚脚本，CI 构件打包剔除全部 U*.sql（ci/artifact-filter.sh）
--       生产故障修复只用正向迁移，禁止在生产执行 U-Undo 脚本
-- 版本号说明：U100002 对应 V100002（任务卡概念 U1，全局实际 U100002）
-- ============================================================

-- 回滚 pj_import_template 建表（本地调试用）
DROP TABLE IF EXISTS pj_import_template;

-- 注意：种子数据 V100003 依赖此表，回滚建表前应先回滚种子
-- 若需完整回滚，配合 U100003__pj_import_template_seed_rollback.sql 使用
