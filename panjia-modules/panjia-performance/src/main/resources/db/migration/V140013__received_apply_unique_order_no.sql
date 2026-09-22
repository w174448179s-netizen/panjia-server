-- V140013：实收审批单加 (period, order_no) 唯一索引
--
-- autoCreateForBatch 改为纯订单号聚合后，原有 uk_rapp_period_contract(period, contract_no)
-- 对 contract_no 为 NULL 的一手房/家装荐客等订单不生效（PG 视 NULL 为互异）。
-- 补 (period, order_no) 唯一索引，保证同一订单同一期间只有一张未完结审批单。

CREATE UNIQUE INDEX IF NOT EXISTS uk_rapp_period_order
    ON pj_perf_received_apply(period, order_no)
    WHERE status IN ('DRAFT','SUBMITTED','APPROVED');
