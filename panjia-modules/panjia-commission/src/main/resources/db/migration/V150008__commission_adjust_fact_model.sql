-- 结佣调整重构：pj_commission_adjust 新增事实模型字段
-- 对齐新签调整（PerformanceAdjust）：直接调整业绩事实（PERF_REAL + PERF_EXPECT）。
-- 复用 new_amount 列存 targetAmount（调整后金额），diff_amount 列存 deltaAmount（调整差额，展示用）。
ALTER TABLE pj_commission_adjust ADD COLUMN IF NOT EXISTS original_amount numeric(18, 2);
ALTER TABLE pj_commission_adjust ADD COLUMN IF NOT EXISTS contract_no varchar(64);
ALTER TABLE pj_commission_adjust ADD COLUMN IF NOT EXISTS fact_type varchar(32);
ALTER TABLE pj_commission_adjust ADD COLUMN IF NOT EXISTS adjust_scope varchar(16);
ALTER TABLE pj_commission_adjust ADD COLUMN IF NOT EXISTS fact_id bigint;
ALTER TABLE pj_commission_adjust ADD COLUMN IF NOT EXISTS target_dept_id bigint;

COMMENT ON COLUMN pj_commission_adjust.original_amount IS '调整前金额（PERF_REAL 合计或单条明细金额）';
COMMENT ON COLUMN pj_commission_adjust.contract_no IS '调整对象合同号';
COMMENT ON COLUMN pj_commission_adjust.fact_type IS '事实口径（PERF_REAL，结佣调整固定为实收业绩）';
COMMENT ON COLUMN pj_commission_adjust.adjust_scope IS '调整范围（CONTRACT 合同级 / DETAIL 明细级）';
COMMENT ON COLUMN pj_commission_adjust.fact_id IS '明细级调整对应的业绩事实 ID（合同级为 NULL）';
COMMENT ON COLUMN pj_commission_adjust.target_dept_id IS '部门划转目标部门 ID（TRANSFER 用）';
