-- =====================================================================
-- 业绩查询 /perf/fact/search 性能优化补索引
--
-- 合同维度聚合时按 (contract_no, period) 取最新结佣申请单。
-- 原唯一索引 uk_capp_period_contract 为 (period, contract_no) 前缀
-- 且带 status 部分条件，无法服务 contract_no IN (...) 的探测。
-- =====================================================================

CREATE INDEX IF NOT EXISTS idx_capp_contract_period
    ON pj_commission_application(contract_no, period, id);
