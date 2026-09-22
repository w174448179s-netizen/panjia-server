-- V120022：pj_normalized_record 新增合同维度冗余字段
--
-- 背景：业绩事实（pj_perf_fact）的查询大量关联 pj_normalized_record → pj_import_raw_signed
--       取合同号/订单号/物业地址/签约时间/费用项，多表 JOIN 拖慢列表页。
--       现把这些字段下沉到归一化记录层，再由业绩引擎快照到 pj_perf_fact，消除关联。
--
-- 字段：
--   order_no         订单号（贝壳原始行 order_no）
--   contract_no      合同号（贝壳原始行 contract_no）
--   property_address 物业地址（raw_json.propertyAddress）
--   sign_date        签约(成销)时间（raw_json.signDate，保留原始字符串，业绩层转 LocalDate）
--   fee_item         费用项（raw_json.feeItem，与 sourceKey 第 4 段同源）

ALTER TABLE pj_normalized_record ADD COLUMN IF NOT EXISTS order_no         VARCHAR(64);
ALTER TABLE pj_normalized_record ADD COLUMN IF NOT EXISTS contract_no      VARCHAR(100);
ALTER TABLE pj_normalized_record ADD COLUMN IF NOT EXISTS property_address VARCHAR(255);
ALTER TABLE pj_normalized_record ADD COLUMN IF NOT EXISTS sign_date        VARCHAR(32);
ALTER TABLE pj_normalized_record ADD COLUMN IF NOT EXISTS fee_item         VARCHAR(100);

COMMENT ON COLUMN pj_normalized_record.order_no         IS '订单号(贝壳原始行 order_no，业绩域快照到 pj_perf_fact.order_no)';
COMMENT ON COLUMN pj_normalized_record.contract_no      IS '合同号(贝壳原始行 contract_no，业绩域快照到 pj_perf_fact.contract_no)';
COMMENT ON COLUMN pj_normalized_record.property_address IS '物业地址(raw_json.propertyAddress，业绩域快照到 pj_perf_fact.property_address)';
COMMENT ON COLUMN pj_normalized_record.sign_date        IS '签约(成销)时间原始字符串(raw_json.signDate，业绩层解析为 pj_perf_fact.business_date)';
COMMENT ON COLUMN pj_normalized_record.fee_item         IS '费用项(raw_json.feeItem，业绩域快照到 pj_perf_fact.fee_item)';

ALTER TABLE pj_normalized_record ADD COLUMN IF NOT EXISTS role_name VARCHAR(64);

COMMENT ON COLUMN pj_normalized_record.role_name IS '角色人姓名(冗余自raw_signed，消除关联)';
