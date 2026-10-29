-- ============================================================
-- 结佣申请单补 order_no 前导索引（2026-10-29）
-- ============================================================
-- 背景：
--   结佣明细列表（listContracts）绑单时按候选合同/订单键双列 OR 查申请单：
--     WHERE period = ? AND (contract_no IN (...) OR order_no IN (...))
--   未选期间时还会跨期查。现有索引：
--     idx_capp_contract_period(contract_no, period, id)  → 可服务 contract_no 分支；
--     idx_capp_period_order(period, order_no)            → 前导列是 period，不带期间条件用不上。
--   因此 order_no 分支退化为顺序扫描，且整表被 OR 后回表取行。
-- 说明：
--   只补缺失索引，不改动既有索引；与 uk_capp_period_order（部分唯一索引，仅活跃状态）
--   语义不同，本条为全状态普通索引，保证作废/驳回单也能按订单号命中。
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_capp_order_period
    ON pj_commission_application(order_no, period, id);
