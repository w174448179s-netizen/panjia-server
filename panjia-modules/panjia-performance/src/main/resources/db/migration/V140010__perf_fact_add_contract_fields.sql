-- V140010：pj_perf_fact 新增合同维度冗余字段
--
-- 背景：业绩查询大量 LEFT JOIN pj_normalized_record → pj_import_raw_signed
--       取合同号/订单号/物业地址/费用项，列表页多表关联性能差。
--       现将这些字段冗余到业绩事实表，导入时由业绩引擎从归一化记录快照落库，
--       后续查询直接读列，消除对 normalized_record / raw_signed 的关联。
--
-- 字段：
--   order_no         订单号
--   contract_no      合同号
--   property_address 物业地址
--   fee_item         费用项
--
-- 注：business_date 列已存在，此前导入时填归属月初；现改为回填实际签约(成销)时间。

ALTER TABLE pj_perf_fact ADD COLUMN IF NOT EXISTS order_no         VARCHAR(64);
ALTER TABLE pj_perf_fact ADD COLUMN IF NOT EXISTS contract_no      VARCHAR(100);
ALTER TABLE pj_perf_fact ADD COLUMN IF NOT EXISTS property_address VARCHAR(255);
ALTER TABLE pj_perf_fact ADD COLUMN IF NOT EXISTS fee_item         VARCHAR(100);

COMMENT ON COLUMN pj_perf_fact.order_no         IS '订单号(冗余自归一化记录，消除关联 raw_signed)';
COMMENT ON COLUMN pj_perf_fact.contract_no      IS '合同号(冗余自归一化记录，消除关联 raw_signed)';
COMMENT ON COLUMN pj_perf_fact.property_address IS '物业地址(冗余自归一化记录，消除关联 raw_signed)';
COMMENT ON COLUMN pj_perf_fact.fee_item         IS '费用项(冗余自归一化记录，消除关联 raw_signed)';

ALTER TABLE pj_perf_fact
ALTER COLUMN business_date TYPE TIMESTAMP
    USING business_date::timestamp;

ALTER TABLE pj_perf_fact ADD COLUMN IF NOT EXISTS role_name VARCHAR(64);
COMMENT ON COLUMN pj_perf_fact.role_name IS '角色人姓名(冗余自归一化记录，消除关联 raw_signed)';

-- 常用查询索引
CREATE INDEX IF NOT EXISTS idx_pfact_contract  ON pj_perf_fact(contract_no, period, fact_type);
CREATE INDEX IF NOT EXISTS idx_pfact_order     ON pj_perf_fact(order_no, period, fact_type);
