-- ============================================================
-- Task-0-6: U-Undo 脚本规约示例
-- 对应：V100005__pj_outbox_idempotent.sql（幂等表）
-- 归属域：panjia-outbox
-- 说明：本地调试用回滚脚本，CI 构件打包剔除全部 U*.sql（ci/artifact-filter.sh）
--       生产故障修复只用正向迁移，禁止在生产执行 U-Undo 脚本
-- 版本号说明：U100005 对应 V100005（任务卡概念 U5，全局实际 U100005）
-- ============================================================

-- 回滚 pj_outbox_idempotent 建表（本地调试用）
DROP TABLE IF EXISTS pj_outbox_idempotent;
