-- pj_perf_received_apply.contract_no 改为可空
-- 背景：一手房/家装荐客等业务类型无真实合同号，以订单号（order_no）为唯一业务锚点；
-- V170001 注释（uk_rapp_period_order 索引说明）已明确「覆盖 contract_no 为 NULL 的一手房/家装荐客等订单」，
-- 但建表时误加 NOT NULL 约束，导致此类实收导入建单时 contract_no=null 被 MyBatis-Plus 跳过、违反约束。
-- 与 pj_received_contract.contract_no（V170001 已定义为可空）保持一致。
-- 唯一索引 uk_rapp_period_contract 为部分索引（WHERE status IN ...），PG 视 NULL 互异，允许多条 NULL 共存；
-- 无合同号单据的幂等由 uk_rapp_period_order（period, order_no）保障。
ALTER TABLE pj_perf_received_apply ALTER COLUMN contract_no DROP NOT NULL;

COMMENT ON COLUMN pj_perf_received_apply.contract_no IS '合同号（一手房/家装荐客等无合同号业务为空，以 order_no 为业务锚点）';
