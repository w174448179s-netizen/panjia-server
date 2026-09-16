-- =====================================================================
-- 业绩查询 /perf/fact/search 性能优化补索引
--
-- 1) pj_perf_fact(source_key, fact_type, fact_status, id)
--    原唯一索引 uk_perf_fact_source_key 是 WHERE fact_status='ACTIVE'
--    的部分索引，无法服务 REVERSED 事实按 source_key 的回查
--    （调整前原始金额、详情弹窗应收/实收配对子查询），导致逐行全表扫描。
--
-- 2) 调整单 / 实收审批单 contract_no 无索引，
--    合同维度聚合时按 (contract_no, period) 取最新单据只能顺序扫描。
-- =====================================================================

CREATE INDEX IF NOT EXISTS idx_pfact_source_key_status
    ON pj_perf_fact(source_key, fact_type, fact_status, id);

CREATE INDEX IF NOT EXISTS idx_padj_contract_period
    ON pj_perf_adjust(contract_no, period, id);

CREATE INDEX IF NOT EXISTS idx_rapp_contract_period
    ON pj_perf_received_apply(contract_no, period, id);
