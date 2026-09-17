-- V120011（原 V100013 移段）：pj_normalized_record 新增 total_received_amount 列（总实收业绩）
-- 贝壳 Excel Q 列"总实收业绩"此前只在 raw_json 中保留，未进入归一化层。
-- 改为按总实收落库 PERF_REAL 后，需在归一化层补齐该字段。
-- 移段原因：该表由本模块 V120004 创建，ALTER 必须排在建表之后；
-- 原段位 V100013 < V120004，空库全量迁移时必然失败（已实测）。

ALTER TABLE pj_normalized_record
ADD COLUMN IF NOT EXISTS total_received_amount NUMERIC(18,2);
