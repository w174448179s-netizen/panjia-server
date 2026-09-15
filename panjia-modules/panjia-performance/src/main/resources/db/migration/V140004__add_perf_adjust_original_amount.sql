
-- 业绩调整单表增加 original_amount 字段（调整前的原始金额）
-- 之前 currentAmount 是实时查询的，已执行的调整单会查到调整后的值，导致展示错误
ALTER TABLE pj_perf_adjust ADD COLUMN IF NOT EXISTS original_amount decimal(18,2) DEFAULT 0 COMMENT '调整前原始金额（创建时快照）';
-- 业绩调整单表：delta_amount 改名为 target_amount
-- 用户录入的是调整后的目标金额，不是变动额；变动额可通过 target - original 反推
ALTER TABLE pj_perf_adjust RENAME COLUMN delta_amount TO target_amount;
